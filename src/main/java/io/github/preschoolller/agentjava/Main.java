package io.github.preschoolller.agentjava;

import io.github.preschoolller.agentjava.backend.maintenance.ShutDownJob;
import io.github.preschoolller.agentjava.backend.maintenance.TaskHandle;
import io.github.preschoolller.agentjava.backend.maintenance.WarmUpStuff;
import io.github.preschoolller.agentjava.backend.provider.ProviderFactory;
import io.github.preschoolller.agentjava.backend.provider.common.AgentMode;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderStreamingResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ReasoningEffortEnum;
import io.github.preschoolller.agentjava.backend.provider.common.RoleEnum;
import io.github.preschoolller.agentjava.backend.provider.common.ThreadPoolFactory;
import io.github.preschoolller.agentjava.backend.provider.common.ToolCall;
import io.github.preschoolller.agentjava.backend.provider.common.event.BaseEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.FunctionToolCallDeltaEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.FunctionToolCallDoneEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.FunctionToolCallStartEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.PoisonEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ReasoningDeltaDoneEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ReasoningDeltaEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ReasoningDeltaStartEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseCompleteEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseDeltaDoneEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseDeltaEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseDeltaStartEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ErrorMessageEvent;
import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.frontend.gui.Window;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import io.github.preschoolller.agentjava.frontend.store.Conversation;
import io.github.preschoolller.agentjava.frontend.store.Message;
import io.github.preschoolller.agentjava.frontend.store.Profile;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class Main {

    /**
     * 会话级 agent 循环锁（id → 本轮链的取消令牌）：不同会话各自并行，同一会话同一时刻最多一轮。
     * UI 的生成态置灰是第一道，这里是后端兜底；令牌随工具续轮传递，整条链结束才移除条目。
     * 各会话各拿各的令牌，续轮间隙不会串轮。
     */
    private static final Map<String, AtomicBoolean> RUNNING = new ConcurrentHashMap<>();

    /** 后台结果送达时会话正忙（抢锁失败）的补偿标记：结果落在当轮历史快照之后，没人消化。
     *  add 先于 tryAcquire 消除间隙竞态——抢锁失败标记留在册，本轮链释放时若会话已空闲
     *  就补一轮让模型看到；用户主动停止的链丢弃标记（结果已在历史里，随下一条用户消息走） */
    private static final Set<String> PENDING_BG_DELIVERY = ConcurrentHashMap.newKeySet();

    /** 工具执行阶段的等待上限：审批等待不计入（那个靠用户点击/停止结束），只防工具 I/O 卡死拖住整轮 */
    private static final long TOOL_EXECUTE_TIMEOUT_SECONDS = 60;

    private static final AppSettings appSettings = AppSettings.get();

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(Main.class);

    private static Window window;

    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {
            window = new Window("Agent");
            // 尺寸/位置/分隔条由 Window 从 AppSettings 恢复（无记录回落居中），关闭行为 Window 自己接管
            window.setVisible(true);

            // 回调触发时本轮用户消息已入库（Window.submitInput 先画卡再回调），
            // 历史快照直接取整个会话，无需单独传问题文本
            window.onInputSubmitted((conversation, text) -> startAgentLoop(conversation, false));

            // 重新生成：先抢会话锁再删旧回答，然后带全量历史重新请求
            window.onRegenerate(conversation -> startAgentLoop(conversation, true));

            window.onStopRequested(() -> {
                // 停的是当前显示的会话：通知那轮链中断；审批等待 200ms 内退出，已生成部分照常落库
                AtomicBoolean token = RUNNING.get(window.getStore().getActive().getId());
                if (token != null) {
                    token.set(true);
                    // 已派发的工具还在后台跑完才收尾（P2-5）：胶囊上说明状态，避免用户以为卡死连点。
                    // 纯文案不改停止语义；链结束 setConversationGenerating(false) 会把胶囊刷回"就绪"
                    window.setRealtimeInfo("正在等待工具收尾…");
                    LOG.info("停止生成......");
                }
            });

            window.setBackGroundTaskControl(new Window.BackGroundTaskControl() {
                @Override
                public boolean kill(String conversationId, String callId) {
                    TaskHandle killed = ShutDownJob.kill(callId);
                    if (killed != null && killed.background()) {
                        SwingUtilities.invokeLater(() ->
                                window.onBackGroundTaskKilled(conversationId, callId));
                    }
                    return killed != null;
                }

                @Override
                public String output(String conversationId, String callId) {
                    return ShutDownJob.getBufferedOutput(callId);
                }
            });
        });
        Runtime.getRuntime().addShutdownHook(new Thread(ShutDownJob::clean));
        // 暂定这里执行。
        WarmUpStuff.warmup();
    }

    /** 抢会话锁：抢到返回本链的取消令牌，该会话已在跑返回 null */
    private static AtomicBoolean tryAcquire(String conversationId) {
        AtomicBoolean token = new AtomicBoolean(false);
        return RUNNING.putIfAbsent(conversationId, token) == null ? token : null;
    }

    private static void startAgentLoop(Conversation conversation, boolean regenerate) {
        AtomicBoolean token = tryAcquire(conversation.getId());
        if (token == null) {
            return; // 该会话已在生成：正常情况 UI 已拦，这里是后端兜底
        }
        if (regenerate) {
            window.getStore().removeLastMessage(conversation.getId());
        }
        PrePareParams preParaParams = getPrePareParams(conversation);
        // 期望这里是唯一调用 loop 的地方
        loop(conversation, token, preParaParams);
    }

    /**
     * 一轮流式回复（agent 循环的单轮）：后台线程从事件队列取增量 → EDT 上回填 →
     * 队列毒丸后等工具收尾，有工具结果就带着同一个取消令牌续下一轮，没有（或已停止）就释放会话锁。
     * 生成的会话不必在前台：卡片经会话 id 定向落库，用户切走再切回由 Window 的 rebind 重新上屏。
     */
    private static void loop(Conversation conversation, AtomicBoolean cancelled, PrePareParams firstRoundParams) {
        CompletableFuture.runAsync(() -> {
            AtomicReference<PrePareParams> preParaParams = new AtomicReference<>(firstRoundParams);
            // 期望若要续轮 loops + 1
            LoopControl loopControl = new LoopControl();
            // 期望这里是唯一调用 doStream 的地方
            while (loopControl.needContinue()) {
                if (loopControl.continueLoop() > 1) {
                    try {
                        SwingUtilities.invokeAndWait(() -> preParaParams.set(getPrePareParams(conversation)));
                        doStream(window, conversation, cancelled, preParaParams.get(), loopControl);
                    } catch (InterruptedException e) {
                        LOG.error("agent loop interrupted", e);
                    } catch (Throwable e) {
                        LOG.error("agent loop exception", e);
                    }
                }
            }
            // 双参 remove：条目还是本轮令牌才清（防御性的对称）
            RUNNING.remove(conversation.getId(), cancelled);
        });
    }

    /**
     * EDT 上取历史快照，勿进行耗时操作
     * @param conversation Conversation
     * @return PrePareParams
     */
    private static PrePareParams getPrePareParams(Conversation conversation) {
        window.setConversationGenerating(conversation.getId(), true);
        // 历史快照与模型/强度/模式选择都在 EDT 上取（此时本轮 AI 占位卡还没建），
        // 之后仓库与下拉框可能被 EDT 修改，后台线程只读这份不可变数据
        List<PromptParams.RoleMessage> history = buildRoleMessages(conversation);
        String model = window.getSelectedModel();
        // 多配置路由：输入区模型下拉跨方案列出所有配置的模型（Window.ModelOption 带所属方案），
        // 选中哪个方案的模型，本轮就走那套配置——key/backendUrl/systemPrompt 从该方案快照，
        // provider 按该方案的 apiType 分派（provider 名全局存 AppSettings，per-apiType）
        String selectedProfile = window.getSelectedModelProfile();
        Profile profile = selectedProfile == null ? null : appSettings.getProfile(selectedProfile);
        if (profile == null) {
            profile = appSettings.findProfileOwningModel(model); // 下拉没带所属方案时按模型名反查
        }
        if (profile == null) {
            // 模型不属于任何方案（理论不可达）的最后兜底：第一个方案（load 保证至少一个）
            profile = appSettings.getProfile(appSettings.getProfileNames().get(0));
        }
        Provider provider = ProviderFactory.createProvider(profile.getApiType());
        ReasoningEffortEnum re = window.getSelectedEffort(); // 模型与思考强度取输入区右下角的选择（对话级设置）
        AgentMode mode = window.getSelectedMode(); // 权限模式快照：本轮中途改下拉框不影响已发出的请求
        // 纯聊天不挂工具，工作区无所谓（保持 null：不建目录、系统提示词不注入）
        String workspace = AgentMode.CHAT == mode ? null : resolveWorkspace(conversation);
        String key = profile.getKey();
        String backendUrl = profile.getBackendUrl();
        String systemPrompt = profile.getSystemPrompt();
        List<String> enabledTools = appSettings.getEnabledTools();
        Window.AiStream stream = window.addAiMessage(conversation.getId(), "");
        PromptParams.Builder paramsBuilder = PromptParams.builder().model(model).re(re)
                .agentMode(mode).workspace(workspace).key(key).backendUrl(backendUrl)
                .systemPrompt(systemPrompt).enabledTools(enabledTools);
        return new PrePareParams(history, provider, stream, paramsBuilder);
    }

    private record PrePareParams(List<PromptParams.RoleMessage> history, Provider provider, Window.AiStream stream, PromptParams.Builder paramsBuilder) {
    }

    private static void doStream(Window window, Conversation conversation, AtomicBoolean cancelled, PrePareParams preParaParams, LoopControl loopControl) {
        BlockingQueue<BaseEvent> events = null;
        AtomicBoolean shouldStopBackend = null;
        List<CompletableFuture<Void>> toolCalls = new ArrayList<>();
        Map<String, Window.ToolCallHandle> callIdHandleMap = new HashMap<>();
        Map<String, String> callIdByItemId = new HashMap<>();
        Map<String, StringBuilder> argsByCallId = new HashMap<>();
        Set<String> doneCalls = new HashSet<>();
        Window.AiStream stream = preParaParams.stream;
        try {
            PromptParams.Builder paramsBuilder = preParaParams.paramsBuilder;
            for (PromptParams.RoleMessage roleMessage : preParaParams.history) {
                paramsBuilder.roleMessages(roleMessage);
            }
            PromptParams promptParams = paramsBuilder.build();
            long roundStartNanos = System.nanoTime(); // 本轮耗时（含 HTTP 建流），胶囊上展示用
            ProviderStreamingResponse providerStreamingResponse = preParaParams.provider.sendPromptStreaming(promptParams);
            events = providerStreamingResponse.blockingQueue();
            shouldStopBackend = providerStreamingResponse.shouldStop();
            BaseEvent event;
            while (!cancelled.get() && !((event = events.take()) instanceof PoisonEvent)) {
                if (event instanceof ReasoningDeltaStartEvent) {
                    // 思考走前端卡片上的独立思考区，开始信号不必再拼进正文
                } else if (event instanceof ReasoningDeltaEvent deltaEvent) {
                    appendThinkingChunk(deltaEvent.delta(), stream, cancelled);
                } else if (event instanceof ReasoningDeltaDoneEvent) {
                    finishThinkingQuietly(stream, cancelled);
                } else if (event instanceof ResponseDeltaStartEvent) {
                    appendAiMessage("", stream, cancelled);
                } else if (event instanceof ResponseDeltaEvent deltaEvent) {
                    appendAiMessage(deltaEvent.delta(), stream, cancelled);
                } else if (event instanceof ResponseDeltaDoneEvent) {
                    appendAiMessage("", stream, cancelled);
                } else if (event instanceof FunctionToolCallStartEvent e) {
                    addToolCall(window, conversation.getId(), e.name(), e.callId(), callIdHandleMap);
                    callIdByItemId.put(e.id(), e.callId());
                    argsByCallId.put(e.callId(), new StringBuilder());
                } else if (event instanceof FunctionToolCallDeltaEvent e) {
                    // 参数增量：事件带的是条目 id，用 Start 事件建立的映射换回 callId 找卡片
                    String callId = callIdByItemId.get(e.id());
                    Window.ToolCallHandle handle = callId == null ? null : callIdHandleMap.get(callId);
                    if (handle == null || cancelled.get()) {
                        continue;
                    }
                    StringBuilder buffered = argsByCallId.get(callId);
                    if (buffered != null) {
                        buffered.append(e.argsDelta()); // 本地累积（后台线程独写），给终态兜底
                    }
                    String delta = e.argsDelta();
                    SwingUtilities.invokeLater(() -> handle.appendArgs(delta)); // 卡片 120ms 节流显示
                } else if (event instanceof FunctionToolCallDoneEvent e) {
                    Window.ToolCallHandle toolCallHandle = callIdHandleMap.get(e.callId());
                    ToolCall toolCall = e.toolCall();
                    toolCall.setApprovalGate((tc, additionalRequest) ->
                            requestToolApproval(tc, promptParams.getAgentMode(), callIdHandleMap, cancelled, additionalRequest));
                    toolCall.setWorkspace(promptParams.getWorkspace());
                    toolCall.setBackGroundTask((callId, output) -> whenBackgroundTaskDone(window, conversation, callId, output));
                    if (toolCallHandle != null) {
                        SwingUtilities.invokeLater(toolCallHandle::markExecuting);
                    }
                    toolCalls.add(performToolCall(toolCall, toolCallHandle));
                    doneCalls.add(e.callId());
                } else if (event instanceof ResponseCompleteEvent complete) {
                    updateUsageInfo(conversation, cancelled, complete.inputTokens(), complete.outputTokens(), roundStartNanos);
                } else if (event instanceof ErrorMessageEvent error) {
                    // 流式错误（visitError/visitFailed/visitIncomplete/SSE 异常发射，后端接线待做）：
                    // 通知气泡展示，流本身照常走到毒丸收束
                    SwingUtilities.invokeLater(() -> window.addNotifyMessage("Error: " + error.message()));
                }
            }
        } catch (RuntimeException | InterruptedException e) {
            SwingUtilities.invokeLater(() -> window.addNotifyMessage("Error: " + e.getMessage()));
        } finally {
            SwingUtilities.invokeLater(stream::finish); // 停止也走 finish：已生成部分照常保留并落库
            if (cancelled.get() && shouldStopBackend != null) {
                shouldStopBackend.set(true);
            }
            for (String callId : callIdHandleMap.keySet()) {
                if (doneCalls.contains(callId)) {
                    continue;
                }
                StringBuilder buffered = argsByCallId.get(callId);
                String partialArgs = buffered == null ? "" : buffered.toString();
                SwingUtilities.invokeLater(() -> {
                    String result = cancelled.get() ? "用户手动取消" : "Error: dangling function call without corresponding item done event";
                    callIdHandleMap.get(callId).finish(result, false, partialArgs);
                });
            }
            // run() 全路径兜底必达终态、执行超时有调度任务兜底，join 不会永久挂起
            toolCalls.forEach(CompletableFuture::join);
            if (cancelled.get()) {
                drainUntilPoison(events);
            }
            if (!cancelled.get() && !toolCalls.isEmpty()) {
                loopControl.requestContinue();
            } else {
                // 后台结果补偿：生成期间送达的结果（抢锁失败留的标记）在链释放、会话已空闲时
                // 补一轮让模型消化——不打断生成态，避免输入区闪烁。用户主动停止的链丢弃标记，
                // 结果已在历史里，随下一条用户消息自然带上
                if (PENDING_BG_DELIVERY.remove(conversation.getId()) && !cancelled.get()) {
                    loopControl.requestContinue();
                }
            }
        }
    }

    private static void whenBackgroundTaskDone(Window window, Conversation conversation, String callId, String output) {
        // bash 后台任务完成回调（bgTask 线程触发）：EDT 上落库+上屏（工具卡回填、注入用户消息），
        // 会话空闲（抢到锁）则自动续一轮让模型消化后台结果；生成中只落库并留补偿标记，
        // 本轮链释放时若已空闲会补一轮——结果落在快照之后也没人理的缝隙由这里兜住
        SwingUtilities.invokeLater(() -> {
            if (window.onBackGroundTaskDone(conversation.getId(), callId, output)) {
                String convId = conversation.getId();
                PENDING_BG_DELIVERY.add(convId); // 先记再抢(startAgentLoop刚开头)：抢锁失败的间隙由链释放兜底
                startAgentLoop(conversation, false);
            }
        });
    }

    private static void updateUsageInfo(Conversation conversation, AtomicBoolean cancelled, long inputTokens, long outputTokens, long roundStartNanos) {
        SwingUtilities.invokeLater(() -> window.setConversationGenerating(conversation.getId(), false));
        if (!cancelled.get() && (inputTokens > 0 || outputTokens > 0)) {
            // 用量上独立胶囊（与状态胶囊分立）：refreshGeneratingUi 刷"就绪"不会再冲掉它。
            // 只在轮主会话正在显示时刷，避免后台会话的用量盖掉前台数据
            String convId = conversation.getId();
            long elapsedMillis = (System.nanoTime() - roundStartNanos) / 1_000_000;
            String summary = "tokens 输入 " + inputTokens + " · 输出 " + outputTokens
                    + " · 耗时 " + (elapsedMillis / 1000.0) + " 秒";
            SwingUtilities.invokeLater(() -> {
                if (convId.equals(window.getStore().getActive().getId())) {
                    window.setUsageInfo(summary);
                }
            });
        }
    }

    /**
     * 审批钩子（P0-4 权限确认卡）：在 provider 的线程池线程上被调用（不在 SSE 读流线程、更不在 EDT），
     * 所以可以在这里阻塞等用户点"允许/拒绝"。策略由 AgentMode 定：该问才问，不该问直接放行。
     * 等点击时每 200ms 醒来看一眼 cancelled——用户点停止不会把等待者吊死。
     */
    private static boolean requestToolApproval(ToolCall toolCall, AgentMode mode,
                                               Map<String, Window.ToolCallHandle> callIdHandleMap,
                                               AtomicBoolean cancelled, boolean additionalRequest) {
        if (!mode.requiresApproval(toolCall.getTool()) && !additionalRequest) {
            return true;
        }
        if (additionalRequest && AgentMode.FULL_ACCESS.equals(mode)) {
            return true;
        }
        LOG.info("审批工具： {}， 是否额外申请：{}", toolCall.getTool().name(), additionalRequest);
        Window.ToolCallHandle handle = callIdHandleMap.get(toolCall.getCallId());
        if (handle == null || cancelled.get()) {
            // 自动拒绝的三个分支之一。出现过"没人点却自动拒绝"（间歇性），每个分支留日志
            // 便于下次 occurrence 定位：卡片缺失 / 用户已停止 / EDT 异常 / 等待被中断
            LOG.info("审批自动拒绝：handle={} cancelled={}",
                    handle == null ? "缺失(callId=" + toolCall.getCallId() + ")" : "在册", cancelled.get());
            return false; // 没有卡片可挂 / 已经叫停：宁可拦下也不静默执行
        }
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        try {
            SwingUtilities.invokeAndWait(() -> handle.requestApproval(decision));
            while (!cancelled.get()) {
                try {
                    return Boolean.TRUE.equals(decision.get(200, TimeUnit.MILLISECONDS));
                } catch (TimeoutException stillWaiting) {
                    // 用户还没点，继续等
                } catch (CancellationException rejected) {
                    LOG.info("审批自动拒绝：decision 被取消");
                    return false;
                } catch (ExecutionException impossible) {
                    LOG.info("审批自动拒绝：decision 异常完成", impossible);
                    return false; // 只有 complete(true/false)，正常到不了
                }
            }
            LOG.info("审批自动拒绝：用户点了停止");
            decision.cancel(true); // 用户点了停止：终结等待，provider 会把这次调用按未放行收尾
            return false;
        } catch (InterruptedException e) {
            LOG.info("审批自动拒绝：等待线程被中断");
            Thread.currentThread().interrupt();
            return false;
        } catch (InvocationTargetException e) {
            // requestApproval 在 EDT 上抛了异常（提醒/重绘层），按未批准处理而非用户拒绝
            LOG.error("审批自动拒绝：EDT 异常 {}", e.getCause() == null ? "" : e.getCause().toString());
            return false;
        }
    }

    private static void appendAiMessage(String chunk, Window.AiStream stream, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return; // 用户点了停止：后续增量丢弃，已生成部分由下面的 finish 落库
        }
        SwingUtilities.invokeLater(() -> stream.appendChunk(chunk));
    }

    /** 思考增量进前端独立思考区：EDT 投递 + 停止截流，与 appendAiMessage 同款模板 */
    private static void appendThinkingChunk(String delta, Window.AiStream stream, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return;
        }
        SwingUtilities.invokeLater(() -> stream.appendThinking(delta));
    }

    /** ReasoningDeltaDone 通知前端收尾：思考区收起为"已思考 N 秒"（不调也行，正文增量会隐式收尾） */
    private static void finishThinkingQuietly(Window.AiStream stream, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return;
        }
        SwingUtilities.invokeLater(stream::finishThinking);
    }

    private static void addToolCall(Window window, String conversationId, String name, String callId, Map<String, Window.ToolCallHandle> callIdHandleMap) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                Window.ToolCallHandle toolCallHandle = window.addToolCall(conversationId, name, "", callId);
                callIdHandleMap.put(callId, toolCallHandle);
            });
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static CompletableFuture<Void> performToolCall(ToolCall toolCall, Window.ToolCallHandle toolCallHandle) {
        // 执行在 Done 事件侧启动：与 Start 同队列同线程消费且 addToolCall 同步登记，卡片句柄此刻必然在册
        ThreadPoolFactory.getToolCallExecutor().execute(toolCall);
        ToolCall.ToolCallStatus status = toolCall.getCombinedStatus();

        // 执行阶段超时：额外审批工具从 extraGatesDone 起算（审批等待不计入），其余从初审通过起算。
        // 一次性调度任务不占常驻线程；completeTerminally 先到先得，正常完成在先则这次写入无效
        CompletableFuture<Void> clockStart = toolCall.getTool().supportsAdditionalApproval()
                ? status.extraGatesDone() : status.executionStarted();
        clockStart.thenRun(() -> ThreadPoolFactory.getTimeoutScheduler().schedule(() ->
                        toolCall.completeTerminally(ToolCall.Status.INCOMPLETE,
                                "工具执行超时（" + TOOL_EXECUTE_TIMEOUT_SECONDS + " 秒未完成），已放弃等待；工具可能仍在后台执行。"),
                TOOL_EXECUTE_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // 终态到达（完成/拒绝/超时/异常）→ EDT 收尾卡片；返回的 future 随终态完成，续轮 forEach(join) 由此收束
        return status.statusFinal().thenAccept(finalStatus -> SwingUtilities.invokeLater(() -> {
            if (toolCallHandle != null) {
                toolCallHandle.finish(toolCall.getResult(),
                        ToolCall.Status.COMPLETE.equals(finalStatus), toolCall.getArguments());
            }
        }));
    }

    /**
     * 会话绑定的工作区落定成绝对路径：没绑过的用默认（~/.agentjava/workspace），顺手把目录建出来
     * （没有 write 工具时用不上，但相对路径读它下面的文件前目录先在着总没错）。建失败只打日志，
     * 工具执行时会自然报错，不在这里拦请求。
     */
    private static String resolveWorkspace(Conversation conversation) {
        String workspace = conversation.getWorkspace();
        if (workspace != null && !workspace.isBlank()) {
            return workspace;
        }
        Path dir = AppSettings.defaultWorkspace();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("创建默认工作区失败: " + e.getMessage());
        }
        return dir.toString();
    }

    /**
     * 会话消息 → 后端轮次列表：空文本跳过，assistant 轮只发正文。
     * 思考内容存在 Message.thinking 独立字段，本来就不进 text，回传天然只带正文
     * （模型每轮重新思考，历史里的 CoT 是噪音，DeepSeek 等服务端甚至直接拒绝回传）。
     */
    private static List<PromptParams.RoleMessage> buildRoleMessages(Conversation conversation) {
        List<PromptParams.RoleMessage> roleMessages = new ArrayList<>();
        for (Message message : conversation.getMessages()) {
            String text = message.text();
            if (text == null || text.isBlank()) {
                continue; // 停止后零增量的空占位卡等
            }
            if (Conversation.ROLE_AI.equals(message.role())) {
                roleMessages.add(new PromptParams.RoleMessage(RoleEnum.ASSISTANT, text));
            } else if (Conversation.ROLE_USER.equals(message.role())) {
                roleMessages.add(new PromptParams.RoleMessage(RoleEnum.USER, text));
            } else if (Conversation.ROLE_TOOL.equals(message.role())) {
                roleMessages.add(new PromptParams.RoleMessage(RoleEnum.TOOL_RESULT, text,
                        message.toolArgs(), message.toolName(), message.callId()));
            }
        }
        return roleMessages;
    }

    /** 用户停止后把剩余增量抽干丢弃，后端读流线程才能走到 finally 放毒丸正常退出，不残留阻塞线程 */
    private static void drainUntilPoison(BlockingQueue<BaseEvent> events) {
        try {
            if (events == null) {
                return;
            }
            BaseEvent event;
            while (true) {
                event = events.poll(2, TimeUnit.SECONDS);
                if (event == null || event instanceof PoisonEvent) {
                    return; // 超时等不到毒丸就放弃（流可能已异常中断）
                }
            }
        } catch (InterruptedException ignored) {
        }
    }

    private static class LoopControl {
        private final AtomicInteger loops = new AtomicInteger(1);
        private int last = 0;

        LoopControl() {
        }

        int requestContinue() {
            return loops.incrementAndGet();
        }

        boolean needContinue() {
            return last == loops.get();
        }

        int continueLoop() {
            return last++;
        }
    }
}

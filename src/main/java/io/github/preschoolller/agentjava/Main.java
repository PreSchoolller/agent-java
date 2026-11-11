package io.github.preschoolller.agentjava;

import io.github.preschoolller.agentjava.backend.context.ContextHandlerManager;
import io.github.preschoolller.agentjava.backend.context.interfaces.ContextHandler;
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
        LoopConfig loopConfig;
        try {
            loopConfig = buildLoopConfig(conversation);
        } catch (Throwable throwable) {
            RUNNING.remove(conversation.getId(), token);
            LOG.error("prepare snapshot params failed: ", throwable);
            SwingUtilities.invokeLater(() -> window.addNotifyMessage(conversation.getId(),
                    "failed to start agent loop: " + throwable.getMessage()));
            return;
        }
        // 链配置在此冻结；每轮消息快照在 loop 内 EDT 上取，后台结果补偿续链在 loop 尾部另起
        loop(conversation, token, loopConfig);
    }

    /**
     * 整条 agent 链（后台线程跑）：配置在 startAgentLoop 冻结为 LoopConfig，每轮经
     * snapshotMessagesOnEdt 重取历史+建卡后 doStream；
     * 工具结果到齐由 doStream requestContinue 续轮（同一个取消令牌、同一把会话锁，生成态保持到整条链结束），
     * 无续轮诉求时链收束：清生成态、放会话锁，再查后台结果补偿标记决定是否补一轮。
     * 生成的会话不必在前台：卡片经会话 id 定向落库，用户切走再切回由 Window 的 rebind 重新上屏。
     */
    private static void loop(Conversation conversation, AtomicBoolean cancelled, LoopConfig loopConfig) {
        CompletableFuture.runAsync(() -> {
            PrepareParams prepareParams;
            LoopControl loopControl = new LoopControl();
            boolean justCompressed = false;
            // 这里是唯一调用 doStream 的地方
            while (loopControl.hasMoreRounds() || justCompressed) {
                int round = loopControl.nextRound();
                try {
                    SwingUtilities.invokeLater(() -> window.setConversationGenerating(conversation.getId(), true));
                    prepareParams = snapshotMessagesOnEdt(conversation, loopConfig); // 每轮快照：只重取历史+建卡，配置不动
                    boolean needCompress = doContextManage(conversation, prepareParams, loopConfig, justCompressed);
                    doStream(window, conversation, cancelled, prepareParams, loopControl);
                    justCompressed = needCompress;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.error("agent loop interrupted", e);
                    break; // 被中断：放弃续轮，已生成部分照常落库
                } catch (Throwable e) {
                    LOG.error("agent loop exception", e);
                    break; // 快照/流式异常：放弃续轮，避免失败快照反复重试
                }
            }
            // 双参 remove：条目还是本轮令牌才清（防御性的对称）
            RUNNING.remove(conversation.getId(), cancelled);
            SwingUtilities.invokeLater(() -> window.setConversationGenerating(conversation.getId(), false));
            LOG.info("loop of {} ends after {} rounds", conversation.getId(), loopControl.executed);
            // 后台结果补偿（顺序关键：先放锁再查标记——bg 回调抢锁失败必然发生在放锁之前，
            // 本查必然能看见它留的标记）。生成期间送达的结果在链释放、会话已空闲时补一轮
            // 让模型消化；用户主动停止的链丢弃标记（结果已在历史里，随下一条用户消息自然带上）
            if (PENDING_BG_DELIVERY.remove(conversation.getId()) && !cancelled.get()) {
                SwingUtilities.invokeLater(() -> startAgentLoop(conversation, false));
                // 抢锁失败：新链已持有会话，其快照必然包含该结果（注入先于标记），不会漏消化
            }
        });
    }

    /**
     * 上下文管理：needCompress 命中（由选中的可修改处理器决策）时，把压缩水位线标记插进
     * 会话（本轮 AI 占位卡之前——占位卡在快照期已落库，摘要稍后填进它；标记排它后面摘要
     * 就落在标记前，下轮裁剪把摘要裁掉），并把标记补记进本轮窗口——插件收到的快照以标记
     * 收尾，压缩范围就此圈定（上个标记+1 → 本标记）。handle 的返回值整体替换本轮发出的
     * 消息列表；标记必须在 handle 正常返回后才落库：插件抛异常时水位线不推进，下一轮仍是
     * 全窗口可重试压缩，避免"标记已落但压缩结果未生成"把没压过的内容裁掉。
     */
    private static boolean doContextManage(Conversation conversation, PrepareParams prepareParams, LoopConfig loopConfig, boolean justCompressed) {
        ArrayList<PromptParams.RoleMessage> roleMessages = new ArrayList<>(prepareParams.paramsBuilder.build().getRoleMessages());
        Conversation.Usage usage = conversation.getUsage();
        long inputTokens = usage == null ? 0 : usage.inputTokens();
        long outputTokens = usage == null ? 0 : usage.outputTokens();
        ContextHandler.ContextDetail contextDetail = new ContextHandler.ContextDetail(roleMessages, inputTokens, outputTokens,
                loopConfig.modelCtxWindow, loopConfig.mHandlerName);
        boolean needCompress = !justCompressed && ContextHandlerManager.needCompress(contextDetail);
        if (needCompress) {
            // contextDetail 补记标记（needCompress 的判定窗口不含标记——决策看的是真实内容）。
            // 标记用 CONTEXT_MARKER 角色：插件按角色识别边界并在返回时剥掉；就算劣质插件
            // 原样透传，provider 序列化对它也是静默跳过，不会污染请求
            roleMessages.add(new PromptParams.RoleMessage(RoleEnum.CONTEXT_MARKER, ContextHandler.MARKER_CONTENT));
            LOG.info("need context compress--------++++++++++----------+++++++++------------++++++++++--------++++++++----------");
        }
        ContextHandler.ContextManaged contextManaged = ContextHandlerManager.handle(contextDetail);
        if (needCompress) {
            // 标记落库（EDT）：必须插在本轮 AI 占位卡之前——占位卡在本轮快照期（appendMessages）
            // 就已落库，早于这里排队的 EDT 任务，尾部追加会排到占位卡后面；压缩摘要作为本轮
            // 响应填进占位卡后，摘要就落在标记前，下轮水位线裁剪会把摘要裁掉（压缩白做）。
            // 入队早于 doStream 的任何 EDT 任务，FIFO 保证占位卡此刻必是最后一条消息
            SwingUtilities.invokeLater(() -> window.getStore().insertContextMarkerBeforeLastAi(conversation.getId()));
        }
        if (justCompressed) {
            return false;
        }
        prepareParams.paramsBuilder.clearRoleMessages();
        contextManaged.roleMessages().forEach(prepareParams.paramsBuilder::roleMessages);
        return needCompress;
    }

    /** 每轮在 EDT 上重取消息快照（配置已由 LoopConfig 冻结，这里只取历史+建卡），勿进行耗时操作 */
    private static PrepareParams snapshotMessagesOnEdt(Conversation conversation, LoopConfig loopConfig)
            throws InterruptedException, InvocationTargetException {
        PrepareParams[] holder = new PrepareParams[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = appendMessages(conversation, loopConfig));
        return holder[0];
    }

    /**
     * EDT 上冻结整条链的请求构造参数：模型/方案路由（key、backendUrl）/思考强度/权限模式/
     * 工具清单/系统提示词，链中途一律不重查——设置变更对下一条链生效，不改动跑着的链
     * （主流 agent 语义：一次 run = 冻结的构造参数 + 演进的消息）。也别在这里读历史/建卡，
     * 那是每轮快照（appendMessages）的事。
     */
    private static LoopConfig buildLoopConfig(Conversation conversation) {
        // 配置都在 EDT 上取：模型/强度/模式读会话记忆（发送时盖戳），后台会话续轮/补偿链
        // 不读全局下拉——不然读到的是当前显示会话的选择（串台）；之后下拉框可能被 EDT 修改，
        // 后台线程只读这份不可变数据
        String model = conversation.getModelName();
        // 多配置路由：输入区模型下拉跨方案列出所有配置的模型（Window.ModelOption 带所属方案），
        // 选中哪个方案的模型，本轮就走那套配置——key/backendUrl/systemPrompt 从该方案快照，
        // provider 按该方案的 apiType 分派（provider 名全局存 AppSettings，per-apiType）
        String selectedProfile = conversation.getModelProfile();
        Profile profile = selectedProfile == null ? null : appSettings.getProfile(selectedProfile);
        if (profile == null) {
            profile = appSettings.findProfileOwningModel(model); // 下拉没带所属方案时按模型名反查
        }
        if (profile == null) {
            // 模型不属于任何方案（理论不可达）的最后兜底：第一个方案（load 保证至少一个）
            profile = appSettings.getProfile(appSettings.getProfileNames().get(0));
        }
        Provider provider = ProviderFactory.createProvider(profile.getApiType());
        // 思考强度/权限模式读会话记忆；旧会话没盖过戳（字段 null）回落输入区当前值
        ReasoningEffortEnum re = conversation.getReasoningEffort() != null
                ? conversation.getReasoningEffort() : window.getSelectedEffort();
        AgentMode mode = conversation.getAgentMode() != null
                ? conversation.getAgentMode() : window.getSelectedMode();
        // 纯聊天不挂工具，工作区无所谓（保持 null：不建目录、系统提示词不注入）
        String workspace = AgentMode.CHAT == mode ? null : resolveWorkspace(conversation);
        String key = profile.getKey();
        String backendUrl = profile.getBackendUrl();
        String systemPrompt = profile.getSystemPrompt();
        int modelCtxWindow = profile.getModelCtxWindow(model);
        List<String> enabledTools = appSettings.getEnabledTools();
        String mHandlerName = appSettings.getActiveContextHandler();
        PromptParams.Builder paramsBuilder = PromptParams.builder().model(model).re(re)
                .agentMode(mode).workspace(workspace).key(key).backendUrl(backendUrl)
                .systemPrompt(systemPrompt).enabledTools(enabledTools);
        return new LoopConfig(provider, paramsBuilder.build(), modelCtxWindow, mHandlerName);
    }

    /**
     * EDT 上装配单轮请求：重取历史快照 + 建本轮 AI 占位卡，与冻结的 LoopConfig 合成。
     * 快照即消费：补偿标记的清除必须和读历史在**同一个 EDT 任务**里——消费挪到 loop 体
     * （bg 线程）时，回调任务（注入结果→加标记）可以插在 remove 和快照之间，读到了结果
     * 却留下标记，链尾补偿再把同一结果消化一遍（幽灵轮）；同任务则 EDT FIFO 保证原子。
     * whenBackgroundTaskDone 先记再抢，抢到锁的链的快照负责消化（对称收口）
     */
    private static PrepareParams appendMessages(Conversation conversation, LoopConfig loopConfig) {
        PENDING_BG_DELIVERY.remove(conversation.getId());
        List<PromptParams.RoleMessage> history = buildRoleMessages(conversation, loopConfig);
        PromptParams.Builder builder = loopConfig.noMessages.toBuilder();
        builder.clearRoleMessages();
        for (PromptParams.RoleMessage roleMessage : history) {
            builder.roleMessages(roleMessage);
        }
        Window.AiStream stream = window.addAiMessage(conversation.getId(), "");
        return new PrepareParams(loopConfig.provider, stream, builder);
    }

    /** 链级冻结配置：provider 实例 + 不含消息的请求模板（PromptParams.Builder(PromptParams) 拷贝列表，
     *  toBuilder 出来的 builder 每轮独立改写消息，不会污染这里）。整条链只读 */
    private record LoopConfig(Provider provider, PromptParams noMessages, int modelCtxWindow, String mHandlerName) {
    }

    private record PrepareParams(Provider provider, Window.AiStream stream, PromptParams.Builder paramsBuilder) {
    }

    /**
     * 一轮流式回复（agent 循环的单轮）：后台线程从事件队列取增量 → EDT 上回填 →
     * 队列毒丸后等工具收尾，有工具结果就 requestContinue 续下一轮（同一个取消令牌、同一把会话锁）。
     */
    private static void doStream(Window window, Conversation conversation, AtomicBoolean cancelled, PrepareParams prepareParams, LoopControl loopControl) {
        BlockingQueue<BaseEvent> events = null;
        AtomicBoolean shouldStopBackend = null;
        List<CompletableFuture<Void>> toolCalls = new ArrayList<>();
        Map<String, Window.ToolCallHandle> callIdHandleMap = new HashMap<>();
        Map<String, String> callIdByItemId = new HashMap<>();
        Map<String, StringBuilder> argsByCallId = new HashMap<>();
        Set<String> doneCalls = new HashSet<>();
        Window.AiStream stream = prepareParams.stream;
        try {
            PromptParams promptParams = prepareParams.paramsBuilder.build();
            long roundStartNanos = System.nanoTime(); // 本轮耗时（含 HTTP 建流），胶囊上展示用
            ProviderStreamingResponse providerStreamingResponse = prepareParams.provider.sendPromptStreaming(promptParams);
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
                    SwingUtilities.invokeLater(() -> window.addNotifyMessage(conversation.getId(),
                            "Error: " + error.message()));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("agent 流式轮被中断", e);
        } catch (RuntimeException e) {
            SwingUtilities.invokeLater(() -> window.addNotifyMessage(conversation.getId(),
                    "Error: " + e.getMessage()));
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
                // 工具续轮：同一个取消令牌、同一把会话锁，生成态保持到整条链结束
                loopControl.requestContinue();
            }
            // 后台结果补偿不在本轮做：查标记必须发生在放锁之后（bg 回调抢锁失败必先于本查），
            // 见 loop 尾部
        }
    }

    private static void whenBackgroundTaskDone(Window window, Conversation conversation, String callId, String output) {
        // bash 后台任务完成回调（bgTask 线程触发）：EDT 上落库+上屏（工具卡回填、注入用户消息），
        // 会话空闲（抢到锁）则自动续一轮让模型消化后台结果；生成中只落库并留补偿标记，
        // 本轮链释放时若已空闲会补一轮——结果落在快照之后也没人理的缝隙由这里兜住
        SwingUtilities.invokeLater(() -> {
            if (window.onBackGroundTaskDone(conversation.getId(), callId, output)) {
                String convId = conversation.getId();
                PENDING_BG_DELIVERY.add(convId); // 先记再抢：抢锁失败的间隙由链释放兜底；抢到了由快照对称清除
                startAgentLoop(conversation, false);
            }
        });
    }

    /** 用量落到会话上（Conversation.Usage，会话级数据源），显示中的会话同步刷胶囊：
     *  后台会话的轮次不丢也不串台，切回时由 showConversation 据此恢复胶囊。
     *  生成态不在这里清：链可能还要续轮，清态统一在 loop 尾部（链收束处） */
    private static void updateUsageInfo(Conversation conversation, AtomicBoolean cancelled, long inputTokens, long outputTokens, long roundStartNanos) {
        if (!cancelled.get() && (inputTokens > 0 || outputTokens > 0)) {
            String convId = conversation.getId();
            double elapsedSeconds = (System.nanoTime() - roundStartNanos) / 1_000_000_000.0;
            Conversation.Usage usage = new Conversation.Usage(inputTokens, outputTokens, elapsedSeconds);
            SwingUtilities.invokeLater(() -> {
                conversation.setUsage(usage);
                if (convId.equals(window.getStore().getActive().getId())) {
                    window.setUsageInfo(usage.label());
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
     * 配置了可修改上下文处理器时按压缩水位线裁剪：只取最后一个标记（不包含）之后的
     * messages 段——标记之前的历史已由插件压缩（摘要以常规消息形态存于标记之后，或由
     * 插件自持），标记本身与更早的消息不进请求（后端 api 请求忽略压缩标记 message，
     * 这里是主裁剪口，provider 序列化还有一道 CONTEXT_MARKER 兜底）。未配置处理器时
     * 不裁剪："不启用"=全量历史，残留的旧标记（处理器后来被停用）不会裁掉内容。
     */
    private static List<PromptParams.RoleMessage> buildRoleMessages(Conversation conversation, LoopConfig loopConfig) {
        List<PromptParams.RoleMessage> roleMessages = new ArrayList<>();
        List<Message> messages = conversation.getMessages();
        int start = 0;
        if (loopConfig.mHandlerName() != null && !loopConfig.mHandlerName().isBlank()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).isContextMarker()) {
                    start = i + 1;
                    break;
                }
            }
        }
        for (int i = start; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message.isContextMarker()) {
                continue; // 压缩标记不进请求（理论上已被水位线裁掉，这里是显式兜底）
            }
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

    /** 链内单线程访问（loop 线程），无需原子类：requested 要跑的总轮数（首轮恒 1，续轮 +1），executed 已跑轮数 */
    private static class LoopControl {
        private int requested = 1;
        private int executed;

        void requestContinue() {
            requested++;
        }

        boolean hasMoreRounds() {
            return executed < requested;
        }

        int nextRound() {
            return ++executed;
        }
    }
}

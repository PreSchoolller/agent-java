package io.github.preschoolller.agentjava;

import io.github.preschoolller.agentjava.backend.provider.ProviderFactory;
import io.github.preschoolller.agentjava.backend.provider.common.AgentMode;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderStreamingResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ReasoningEffortEnum;
import io.github.preschoolller.agentjava.backend.provider.common.RoleEnum;
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
import io.github.preschoolller.agentjava.frontend.store.Conversation;
import io.github.preschoolller.agentjava.frontend.store.Message;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
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
     * 旧版全 app 共用一个 AtomicBoolean，续轮间隙输入解锁后新请求会把旧轮的取消位复位，
     * 旧轮收尾时误判"没取消"再续一轮——并行请求就是这么来的，现在各会话各拿各的令牌。
     */
    private static final Map<String, AtomicBoolean> RUNNING = new ConcurrentHashMap<>();

    /** 工具执行阶段的等待上限：审批等待不计入（那个靠用户点击/停止结束），只防工具 I/O 卡死拖住整轮 */
    private static final long TOOL_EXECUTE_TIMEOUT_SECONDS = 60;

    public static void main(String[] args) {
        Provider openAiProvider = ProviderFactory.createOpenAiProvider();
        SwingUtilities.invokeLater(() -> {
            Window window = new Window("Agent");
            // 尺寸/位置/分隔条由 Window 从 AppSettings 恢复（无记录回落居中），关闭行为 Window 自己接管
            window.setVisible(true);

            // 回调触发时本轮用户消息已入库（Window.submitInput 先画卡再回调），
            // 历史快照直接取整个会话，无需单独传问题文本
            window.onInputSubmitted((conversation, text) -> startAgentLoop(window, conversation, openAiProvider));

            // 重新生成：先抢会话锁再删旧回答，然后带全量历史重新请求
            window.onRegenerate(conversation -> {
                AtomicBoolean token = tryAcquire(conversation.getId());
                if (token == null) {
                    return; // 该会话已在生成（理论上不可达：生成中按钮隐藏），不动仓库
                }
                window.getStore().removeLastMessage(conversation.getId());
                streamReply(window, conversation, openAiProvider, token);
            });

            window.onStopRequested(() -> {
                // 停的是当前显示的会话：通知那轮链中断；审批等待 200ms 内退出，已生成部分照常落库
                AtomicBoolean token = RUNNING.get(window.getStore().getActive().getId());
                if (token != null) {
                    token.set(true);
                    // 已派发的工具还在后台跑完才收尾（P2-5）：胶囊上说明状态，避免用户以为卡死连点。
                    // 纯文案不改停止语义；链结束 setConversationGenerating(false) 会把胶囊刷回"就绪"
                    window.setRealtimeInfo("正在等待工具收尾…");
                }
            });
        });
    }

    /** 抢会话锁：抢到返回本链的取消令牌，该会话已在跑返回 null */
    private static AtomicBoolean tryAcquire(String conversationId) {
        AtomicBoolean token = new AtomicBoolean(false);
        return RUNNING.putIfAbsent(conversationId, token) == null ? token : null;
    }

    private static void startAgentLoop(Window window, Conversation conversation, Provider provider) {
        AtomicBoolean token = tryAcquire(conversation.getId());
        if (token == null) {
            return; // 该会话已在生成：正常情况 UI 已拦，这里是后端兜底
        }
        streamReply(window, conversation, provider, token);
    }

    /**
     * 一轮流式回复（agent 循环的单轮）：EDT 上取历史快照 → 后台线程从事件队列取增量 → EDT 上回填 →
     * 队列毒丸后等工具收尾，有工具结果就带着同一个取消令牌续下一轮，没有（或已停止）就释放会话锁。
     * 生成的会话不必在前台：卡片经会话 id 定向落库，用户切走再切回由 Window 的 rebind 重新上屏。
     */
    private static void streamReply(Window window, Conversation conversation, Provider provider, AtomicBoolean cancelled) {
        window.setConversationGenerating(conversation.getId(), true);
        // 历史快照与模型/强度/模式选择都在 EDT 上取（此时本轮 AI 占位卡还没建），
        // 之后仓库与下拉框可能被 EDT 修改，后台线程只读这份不可变数据
        List<PromptParams.RoleMessage> history = buildRoleMessages(conversation);
        String model = window.getSelectedModel();
        ReasoningEffortEnum re = window.getSelectedEffort(); // 模型与思考强度取输入区右下角的选择（对话级设置）
        AgentMode mode = window.getSelectedMode(); // 权限模式快照：本轮中途改下拉框不影响已发出的请求
        // 纯聊天不挂工具，工作区无所谓（保持 null：不建目录、系统提示词不注入）
        String workspace = AgentMode.CHAT == mode ? null : resolveWorkspace(conversation);
        Window.AiStream stream = window.addAiMessage(conversation.getId(), "");
        long roundStartNanos = System.nanoTime(); // 本轮耗时（含 HTTP 建流），胶囊上展示用
        CompletableFuture.runAsync(() -> {
            BlockingQueue<BaseEvent> events = null;
            List<CompletableFuture<Void>> toolCalls = new ArrayList<>();
            Map<String, Window.ToolCallHandle> callIdHandleMap = new HashMap<>();
            Map<String, String> callIdByItemId = new HashMap<>();
            Map<String, StringBuilder> argsByCallId = new HashMap<>();
            Set<String> doneCalls = new HashSet<>();
            long inputTokens = 0;
            long outputTokens = 0;
            try {
                PromptParams.Builder paramsBuilder = PromptParams.builder().model(model).re(re)
                        .agentMode(mode).workspace(workspace).approvalGate((toolCall, agentMode) ->
                                requestToolApproval(toolCall, agentMode, callIdHandleMap, cancelled));
                for (PromptParams.RoleMessage roleMessage : history) {
                    paramsBuilder.roleMessages(roleMessage);
                }
                ProviderStreamingResponse providerStreamingResponse = provider.sendPromptStreaming(paramsBuilder.build());
                events = providerStreamingResponse.blockingQueue();
                BaseEvent event;
                while (!cancelled.get() && !((event = events.take()) instanceof PoisonEvent)) {
                    if (event instanceof ReasoningDeltaStartEvent) {
                        appendAiMessage("<think>", stream, cancelled);
                    } else if (event instanceof ReasoningDeltaEvent deltaEvent) {
                        appendAiMessage(deltaEvent.delta(), stream, cancelled);
                    } else if (event instanceof ReasoningDeltaDoneEvent) {
                        appendAiMessage("</think>", stream, cancelled);
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
                        toolCalls.add(performToolCall(toolCall, toolCallHandle));
                        doneCalls.add(e.callId());
                    } else if (event instanceof ResponseCompleteEvent complete) {
                        inputTokens = complete.inputTokens();
                        outputTokens = complete.outputTokens();
                    } else if (event instanceof ErrorMessageEvent error) {
                        // 流式错误（visitError/visitFailed/visitIncomplete/SSE 异常发射，后端接线待做）：
                        // 通知气泡展示，流本身照常走到毒丸收束
                        SwingUtilities.invokeLater(() -> window.addNotifyMessage("Error: " + error.message()));
                    }
                }
            } catch (RuntimeException e) {
                SwingUtilities.invokeLater(() -> window.addNotifyMessage("Error: " + e.getMessage()));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                SwingUtilities.invokeLater(() -> stream.finish()); // 停止也走 finish：已生成部分照常保留并落库
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
                // 审批阶段由点击/停止结束，执行阶段有超时兜底，这里不会永久挂起
                toolCalls.forEach(CompletableFuture::join);
                if (cancelled.get()) {
                    drainUntilPoison(events);
                }
                if (!cancelled.get() && !toolCalls.isEmpty()) {
                    // 工具续轮：同一个取消令牌、同一把会话锁，生成态保持到整条链结束
                    SwingUtilities.invokeLater(() -> streamReply(window, conversation, provider, cancelled));
                } else {
                    // 双参 remove：条目还是本轮令牌才清（防御性的对称）
                    RUNNING.remove(conversation.getId(), cancelled);
                    SwingUtilities.invokeLater(() -> window.setConversationGenerating(conversation.getId(), false));
                    if (!cancelled.get() && (inputTokens > 0 || outputTokens > 0)) {
                        // 用量上胶囊（排在生成态刷新之后：refreshGeneratingUi 会把胶囊刷回"就绪"）。
                        // 只在轮主会话正在显示时刷，避免后台会话的用量盖掉前台状态
                        String convId = conversation.getId();
                        long elapsedMillis = (System.nanoTime() - roundStartNanos) / 1_000_000;
                        String summary = "tokens 输入 " + inputTokens + " · 输出 " + outputTokens
                                + " · 耗时 " + (elapsedMillis / 1000.0) + " 秒";
                        SwingUtilities.invokeLater(() -> {
                            if (convId.equals(window.getStore().getActive().getId())) {
                                window.setRealtimeInfo(summary);
                            }
                        });
                    }
                }
            }
        });
    }

    /**
     * 审批钩子（P0-4 权限确认卡）：在 provider 的线程池线程上被调用（不在 SSE 读流线程、更不在 EDT），
     * 所以可以在这里阻塞等用户点"允许/拒绝"。策略由 AgentMode 定：该问才问，不该问直接放行。
     * 等点击时每 200ms 醒来看一眼 cancelled——用户点停止不会把等待者吊死。
     */
    private static boolean requestToolApproval(ToolCall toolCall, AgentMode mode,
                                               Map<String, Window.ToolCallHandle> callIdHandleMap,
                                               AtomicBoolean cancelled) {
        if (!mode.requiresApproval(toolCall.getTool())) {
            return true;
        }
        Window.ToolCallHandle handle = callIdHandleMap.get(toolCall.getCallId());
        if (handle == null || cancelled.get()) {
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
                    return false;
                } catch (ExecutionException impossible) {
                    return false; // 只有 complete(true/false)，正常到不了
                }
            }
            decision.cancel(true); // 用户点了停止：终结等待，provider 会把这次调用按未放行收尾
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (InvocationTargetException e) {
            return false;
        }
    }

    private static void appendAiMessage(String chunk, Window.AiStream stream, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return; // 用户点了停止：后续增量丢弃，已生成部分由下面的 finish 落库
        }
        SwingUtilities.invokeLater(() -> stream.appendChunk(chunk));
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
        return CompletableFuture.runAsync(() -> {
            try {
                toolCall.awaitFinal(TOOL_EXECUTE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                System.out.println("工具调用：" + toolCall.getTool().name() + " call_id: " + toolCall.getCallId() + "错误！");
            } catch (TimeoutException e) {
                // 执行阶段超时：必须给模型和卡片一句交代——不写的话 finish(null) 卡片空白，回传历史时整条被跳过
                String timeoutResult = "工具执行超时（" + TOOL_EXECUTE_TIMEOUT_SECONDS + " 秒未完成），已放弃等待；工具可能仍在后台执行。";
                toolCall.setResult(timeoutResult);
                toolCall.setStatus(ToolCall.Status.INCOMPLETE);
                SwingUtilities.invokeLater(() -> toolCallHandle.finish(timeoutResult, false, toolCall.getArguments()));
                return;
            }
            SwingUtilities.invokeLater(() -> {
                toolCallHandle.finish(toolCall.getResult(),
                        ToolCall.Status.COMPLETE.equals(toolCall.getStatus()), toolCall.getArguments());
            });
        });
    }

    /**
     * 会话绑定的工作区落定成绝对路径：没绑过的用默认（~/.agentjava/workspace），顺手把目录建出来
     * （没有 write 工具的现在用不上，但相对路径读它下面的文件前目录先在着总没错）。建失败只打日志，
     * 工具执行时会自然报错，不在这里拦请求。
     */
    private static String resolveWorkspace(Conversation conversation) {
        String workspace = conversation.getWorkspace();
        if (workspace != null && !workspace.isBlank()) {
            return workspace;
        }
        java.nio.file.Path dir = io.github.preschoolller.agentjava.frontend.store.AppSettings.defaultWorkspace();
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            System.err.println("创建默认工作区失败: " + e.getMessage());
        }
        return dir.toString();
    }

    /**
     * 会话消息 → 后端轮次列表：空文本跳过，assistant 轮剥掉思考块
     * （模型每轮重新思考，历史里的 CoT 是噪音，DeepSeek 等服务端甚至直接拒绝回传）。
     * 思考块剥离本质是 API 编码决策，后端 buildParams 稳定后可挪到那边。
     */
    private static List<PromptParams.RoleMessage> buildRoleMessages(Conversation conversation) {
        List<PromptParams.RoleMessage> roleMessages = new ArrayList<>();
        for (Message message : conversation.getMessages()) {
            String text = message.text();
            if (text == null || text.isBlank()) {
                continue; // 停止后零增量的空占位卡等
            }
            if (Conversation.ROLE_AI.equals(message.role())) {
                text = stripThink(text);
                if (text.isBlank()) {
                    continue; // 整条都是思考内容被剥光
                }
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

    /** 剥掉 assistant 文本里的思考块：成对的整段删，流式停止导致的未闭合块剥到文本尾 */
    private static String stripThink(String text) {
        String stripped = text.replaceAll("(?s)<think>.*?</think>", "");
        int open = stripped.indexOf("<think>");
        if (open >= 0) {
            stripped = stripped.substring(0, open);
        }
        return stripped.strip();
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
}

package io.github.preschoolller.agentjava;

import io.github.preschoolller.agentjava.backend.provider.ProviderFactory;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderStreamingResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ReasoningEffortEnum;
import io.github.preschoolller.agentjava.backend.provider.common.RoleEnum;
import io.github.preschoolller.agentjava.backend.provider.common.ToolCall;
import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.frontend.gui.Window;
import io.github.preschoolller.agentjava.frontend.store.Conversation;
import io.github.preschoolller.agentjava.frontend.store.Message;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.preschoolller.agentjava.backend.provider.common.Constants.POISON;

public class Main {
    public static void main(String[] args) {
        Provider openAiProvider = ProviderFactory.createOpenAiProvider();
        SwingUtilities.invokeLater(() -> {
            Window window = new Window("Agent");
            // 关闭行为由 Window 自己接管（EXIT_ON_CLOSE 或最小化到托盘，设置里配置）
            window.setSize(1280, 720);
            window.setLocation(640, 360);
            window.setVisible(true);

            AtomicBoolean cancelled = new AtomicBoolean(false);

            // 回调触发时本轮用户消息已入库（Window.submitInput 先画卡再回调），
            // 历史快照直接取整个会话，无需单独传问题文本
            window.onInputSubmitted((conversation, text) ->
                    realReplyStream(window, cancelled, conversation, openAiProvider));

            // 重新生成：回调触发时旧回复已从界面移除，先删仓库里的旧回答，再带全量历史重新请求
            window.onRegenerate(conversation -> {
                window.getStore().removeLastMessage(conversation.getId());
                realReplyStream(window, cancelled, conversation, openAiProvider);
            });

            window.onStopRequested(() -> {
                // 通知后端中断本次生成；已生成的部分由 realReplyStream 里的 finish 保留并照常落库
                cancelled.set(true);
            });
        });
    }

    /** 真实流式回复：EDT 上取历史快照 → 后台线程从 BlockingQueue 取增量 → EDT 上 appendChunk（Window 内部 120ms 节流）→ 毒丸后 finish 落库 */
    private static void realReplyStream(Window window, AtomicBoolean cancelled, Conversation conversation, Provider provider) {
        window.setGenerating(true);
        cancelled.set(false);
        // 历史快照与模型/强度选择都在 EDT 上取（此时本轮 AI 占位卡还没建），
        // 之后仓库与下拉框可能被 EDT 修改，后台线程只读这份不可变数据
        List<PromptParams.RoleMessage> history = buildRoleMessages(conversation);
        String model = window.getSelectedModel();
        ReasoningEffortEnum re = window.getSelectedEffort(); // 模型与思考强度取输入区右下角的选择（对话级设置）
        Window.AiStream stream = window.addAiMessage(""); // 这一步已在 EDT 上（输入/按钮回调都在 EDT）
        CompletableFuture.runAsync(() -> {
            boolean stopped = false;
            BlockingQueue<String> chunks = null;
            try {
                PromptParams.Builder paramsBuilder = PromptParams.builder().model(model).re(re);
                for (PromptParams.RoleMessage roleMessage : history) {
                    paramsBuilder.roleMessages(roleMessage);
                }
                ProviderStreamingResponse providerStreamingResponse = provider.sendPromptStreaming(paramsBuilder.build());
                chunks = providerStreamingResponse.blockingQueue();
                while (true) {
                    String chunk = chunks.take(); // 阻塞等下一个增量，节奏由模型决定
                    if (POISON.equals(chunk)) {
                        ToolCall toolCall = providerStreamingResponse.toolCall();
                        SwingUtilities.invokeLater(() -> performToolCall(window, toolCall, cancelled, conversation, provider));
                        break;
                    }
                    if (cancelled.get()) {
                        stopped = true; // 用户点了停止：后续增量丢弃，已生成部分由下面的 finish 落库
                        break;
                    }
                    SwingUtilities.invokeLater(() -> stream.appendChunk(chunk));
                }
            } catch (InterruptedException e) {
                stopped = true;
            } catch (RuntimeException e) {
                SwingUtilities.invokeLater(() -> {
                    stream.finish();
                    window.addNotifyMessage("Error: " + e.getMessage());
                    window.setGenerating(false);
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    stream.finish(); // 停止也走 finish：已生成部分照常保留并落库
                    window.setGenerating(false);
                });
                if (stopped) {
                    drainUntilPoison(chunks);
                }
            }
        });
    }

    private static void performToolCall(Window window, ToolCall toolCall, AtomicBoolean cancelled, Conversation conversation, Provider provider) {
        System.out.println("performToolCall：" + System.currentTimeMillis());
        if (toolCall.getStatus() == null) {
            return;
        }
        // TODO 此处可能还没设置，唉，咋办呢？
        if (ToolCall.Status.EMPTY.equals(toolCall.getStatus())) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                System.out.println("等待工具调用：错误！");
            }
        }
        if (ToolCall.Status.EMPTY.equals(toolCall.getStatus()) || toolCall.getStatus() == null) {
            return;
        }

        // 工具调用
        System.out.println("添加工具卡片：" + System.currentTimeMillis());
        Window.ToolCallHandle toolCallHandle = window.addToolCall(toolCall.getTool().name(), toolCall.getArguments(), toolCall.getCallId());
        CompletableFuture.runAsync(() -> {
            while (ToolCall.Status.IN_PROGRESS.equals(toolCall.getStatus())) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    System.out.println("工具调用：" + toolCall.getTool().name() + " call_id: " + toolCall.getCallId() + "错误！");
                    break;
                }
            }
            SwingUtilities.invokeLater(() -> {
                        toolCallHandle.finish(toolCall.getResult(),
                                ToolCall.Status.COMPLETE.equals(toolCall.getStatus()));
                        if (cancelled.get()) {
                            return;
                        }
                        realReplyStream(window, cancelled, conversation, provider);
                    }
            );
        });
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
    private static void drainUntilPoison(BlockingQueue<String> chunks) {
        try {
            String chunk;
            while (!POISON.equals(chunk = chunks.poll(2, TimeUnit.SECONDS))) {
                if (chunk == null) {
                    return; // 超时等不到毒丸就放弃（流可能已异常中断）
                }
            }
        } catch (InterruptedException ignored) {
        }
    }
}

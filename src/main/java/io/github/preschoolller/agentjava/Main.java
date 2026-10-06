package io.github.preschoolller.agentjava;

import io.github.preschoolller.agentjava.backend.provider.ProviderFactory;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.frontend.gui.Window;
import io.github.preschoolller.agentjava.frontend.store.Conversation;
import io.github.preschoolller.agentjava.frontend.store.Message;

import javax.swing.SwingUtilities;
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

            window.onInputSubmitted((conversation, text) ->
                    realReplyStream(window, cancelled, text, openAiProvider));

            // 重新生成：回调触发时旧回复已从界面移除，先删仓库里的旧回答，
            // 再对会话里最后一条用户消息重新发起流式请求
            window.onRegenerate(conversation -> {
                window.getStore().removeLastMessage(conversation.getId());
                realReplyStream(window, cancelled, lastUserText(conversation), openAiProvider);
            });

            window.onStopRequested(() -> {
                // 通知后端中断本次生成；已生成的部分由 realReplyStream 里的 finish 保留并照常落库
                cancelled.set(true);
            });
        });
    }

    /** 真实流式回复：后台线程从 BlockingQueue 取增量 → EDT 上 appendChunk（Window 内部 120ms 节流）→ 毒丸后 finish 落库 */
    private static void realReplyStream(Window window, AtomicBoolean cancelled, String question, Provider provider) {
        window.setGenerating(true);
        cancelled.set(false);
        Window.AiStream stream = window.addAiMessage(""); // 这一步已在 EDT 上（输入/按钮回调都在 EDT）
        CompletableFuture.runAsync(() -> {
            boolean stopped = false;
            BlockingQueue<String> chunks = null;
            try {
                // 模型与思考强度取输入区右下角的选择（对话级设置）
                PromptParams promptParams = new PromptParams(
                        window.getSelectedModel(), question, window.getSelectedEffort());
                chunks = provider.sendPromptStreaming(promptParams);
                while (true) {
                    String chunk = chunks.take(); // 阻塞等下一个增量，节奏由模型决定
                    if (POISON.equals(chunk)) {
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
                return;
            }
            SwingUtilities.invokeLater(() -> {
                stream.finish(); // 停止也走 finish：已生成部分照常保留并落库
                window.setGenerating(false);
            });
            if (stopped) {
                drainUntilPoison(chunks);
            }
        });
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

    /** 会话里最后一条用户消息的文本（重新生成时即本次要重新请求的问题） */
    private static String lastUserText(Conversation conversation) {
        List<Message> messages = conversation.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (Conversation.ROLE_USER.equals(messages.get(i).role())) {
                return messages.get(i).text();
            }
        }
        return "";
    }
}

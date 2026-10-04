package io.github.preschoolller.agentjava;

import io.github.preschoolller.agentjava.gui.Window;

import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Window window = new Window("Agent");
            window.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            window.setSize(1280, 720);
            window.setLocation(640, 360);
            window.setVisible(true);

            // ===== 后端接入示例：把下面的模拟调用换成你自己的后端请求即可 =====
            AtomicBoolean cancelled = new AtomicBoolean(false);

            window.onInputSubmitted((conversation, text) ->
                    simulateReply(window, cancelled, sampleReply(text)));

            // 重新生成：回调触发时旧回复已从界面移除，先删仓库里的旧回答，再重新请求
            window.onRegenerate(conversation -> {
                window.getStore().removeLastMessage(conversation.getId());
                simulateReply(window, cancelled, sampleReply("（重新生成）"));
            });

            window.onStopRequested(() -> {
                // TODO: 通知后端中断本次生成；示例里置个标记，已生成的部分保留并照常落库
                cancelled.set(true);
            });
        });
    }

    /** 模拟一次流式回复：起空卡片 → 逐段 appendChunk（Window 内部按 120ms 节流渲染）→ finish 落库 */
    private static void simulateReply(Window window, AtomicBoolean cancelled, String markdown) {
        window.setGenerating(true);
        cancelled.set(false);
        Window.AiStream stream = window.addAiMessage(""); // 这一步已在 EDT 上（输入/按钮回调都在 EDT）
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(400); // 模拟首字延迟
                for (String chunk : markdown.split("(?<=\n)")) {
                    if (cancelled.get()) {
                        break; // 用户点了停止：停止追加，已生成部分由下面的 finish 落库
                    }
                    Thread.sleep(60);
                    SwingUtilities.invokeLater(() -> stream.appendChunk(chunk));
                }
                SwingUtilities.invokeLater(() -> {
                    stream.finish();
                    window.setGenerating(false);
                });
            } catch (InterruptedException ignored) {
            }
        });
    }

    /** 模拟回复：顺便当 Markdown 渲染的演示样例，替换成你的后端请求 */
    private static String sampleReply(String question) {
        return "已收到：「" + question + "」。\n\n"
                + "## 回答要点\n\n"
                + "- **结构化输出**：标题、`行内代码`、[链接](https://example.com)、~~删除线~~ 都支持\n"
                + "- 代码块带浅灰圆角底、语言标注和复制按钮：\n\n"
                + "```java\n"
                + "public static void main(String[] args) {\n"
                + "    System.out.println(\"把这段模拟调用换成你的后端请求\");\n"
                + "}\n"
                + "```\n\n"
                + "- [x] 流式渲染已按 120ms 节流\n"
                + "- [ ] 把 `Main` 里的模拟调用换成真实后端\n\n"
                + "> 提示：点回复右下角的「重新生成」可以重新请求一次。\n\n"
                + "---\n\n"
                + "以上为前端模拟回复，配置在设置面板里维护。";
    }
}

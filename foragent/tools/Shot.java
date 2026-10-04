package foragent.tools;

import io.github.preschoolller.agentjava.gui.SettingsDialog;
import io.github.preschoolller.agentjava.gui.Sidebar;
import io.github.preschoolller.agentjava.gui.Window;
import io.github.preschoolller.agentjava.store.Conversation;
import io.github.preschoolller.agentjava.store.ConversationStore;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 前端回归工具（提交在库里，产物落在 gitignore 的 target/ 下）。每次改完 UI 跑一遍：
 * <pre>
 *   mvn -q compile
 *   javac -encoding UTF-8 -cp target/classes -d target/classes foragent/tools/Shot.java
 *   java -cp target/classes foragent.tools.Shot D:/CodingSpace/IdeaProjects/agent-java/target/window
 * </pre>
 * 输出：持久化往返测试结果（PERSIST_OK）+ 六张截图
 * window-{1展开,1b拖宽侧栏,2切换会话,3生成中,4折叠,5设置}.png（SHOT_OK）。
 * 注意：Swing 视图树非线程安全，所有 UI 操作必须像下面这样包在 EDT 里执行（见踩坑记录 #1）。
 */
public class Shot {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("agentjava-verify");

        // —— 持久化往返（含特殊字符），纯模型操作，不涉及 UI ——
        ConversationStore first = new ConversationStore(dir.resolve("history.json"));
        Conversation conv = first.newConversation();
        first.appendMessage(conv.getId(), Conversation.ROLE_USER, "带\"引号\"、\n换行、\t制表和中文✓的消息");
        first.appendMessage(conv.getId(), Conversation.ROLE_AI, "回复");
        first.rename(conv.getId(), "往返测试标题");
        ConversationStore second = new ConversationStore(dir.resolve("history.json"));
        expect(second.getConversations().size() == 1, "会话数");
        expect(second.getActive().getMessages().size() == 2, "消息数");
        expect(second.getActive().getMessages().get(0).text().contains("\n"), "换行保留");
        expect("往返测试标题".equals(second.getActive().getTitle()), "标题");
        System.out.println("PERSIST_OK");

        // —— 造两段会话（c1 六条消息让瀑布流溢出，滚动条才可见） ——
        ConversationStore store = new ConversationStore(dir.resolve("shot.json"));
        Conversation c1 = store.newConversation();
        store.appendMessage(c1.getId(), Conversation.ROLE_USER, "帮我看看这段代码有什么问题");
        store.appendMessage(c1.getId(), Conversation.ROLE_AI,
                "好的，请把代码贴出来。\n收到后我会逐段分析并给出修改建议，重点关注空指针风险、资源关闭顺序以及并发场景下的可见性问题，这几类问题在实际项目里最常见。");
        store.appendMessage(c1.getId(), Conversation.ROLE_USER, "为什么并发场景下可见性会出问题？");
        store.appendMessage(c1.getId(), Conversation.ROLE_AI,
                "因为每个线程可能把共享变量缓存在自己的工作内存里，写回主内存的时机不确定，其他线程读到的可能是旧值。"
                        + "解决思路是使用 volatile、锁或者原子类来建立 happens-before 关系，保证一个线程的写对另一个线程立即可见。");
        store.appendMessage(c1.getId(), Conversation.ROLE_USER, "volatile 能保证原子性吗？");
        store.appendMessage(c1.getId(), Conversation.ROLE_AI, "不能。volatile 只保证可见性和禁止指令重排，像 i++ 这种复合操作还是需要原子类或锁。");
        Conversation c2 = store.newConversation();
        store.appendMessage(c2.getId(), Conversation.ROLE_USER, "总结一下今天聊的内容");
        store.appendMessage(c2.getId(), Conversation.ROLE_AI, "今天聊了前端布局的三点改动。");

        edt(() -> {
            Window window = new Window("Agent", store);
            window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            window.setSize(1280, 720);
            window.setVisible(true);
            WINDOW = window;
        });
        Thread.sleep(1500);
        capture(args[0] + "-1-expanded.png");

        // —— 截图 1b：分隔条拖到 360px（模拟手动拖宽侧栏） ——
        edt(() -> WINDOW.getSplitPane().setDividerLocation(360));
        Thread.sleep(600);
        capture(args[0] + "-1b-wide.png");
        edt(() -> WINDOW.getSplitPane().setDividerLocation(Sidebar.EXPANDED_WIDTH));
        Thread.sleep(400);

        // —— 截图 2：切换到第一段对话（用户气泡靠右 + 长文本高度自适应 + 滚到底部） ——
        edt(() -> WINDOW.getStore().switchTo(c1.getId()));
        Thread.sleep(1200);
        capture(args[0] + "-2-switched.png");

        // —— 截图 3：生成中状态（停止按钮 + 胶囊文案） ——
        edt(() -> WINDOW.setGenerating(true));
        Thread.sleep(500);
        capture(args[0] + "-3-generating.png");
        edt(() -> WINDOW.setGenerating(false));

        // —— 截图 4：折叠侧栏 ——
        edt(() -> {
            WINDOW.getSidebar().setExpanded(false);
            WINDOW.getSplitPane().setDividerLocation(Sidebar.COLLAPSED_WIDTH);
            WINDOW.getContentPane().revalidate();
        });
        Thread.sleep(800);
        capture(args[0] + "-4-collapsed.png");

        // —— 截图 5：设置面板 ——
        SettingsDialog[] dialogHolder = new SettingsDialog[1];
        edt(() -> {
            SettingsDialog dialog = new SettingsDialog(WINDOW, false);
            dialog.setVisible(true);
            dialogHolder[0] = dialog;
        });
        Thread.sleep(600);
        edt(() -> {
            SettingsDialog dialog = dialogHolder[0];
            BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
            dialog.printAll(image.createGraphics());
            try {
                ImageIO.write(image, "png", new File(args[0] + "-5-settings.png"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            dialog.dispose();
        });

        edt(() -> WINDOW.dispose());
        System.out.println("SHOT_OK");
        System.exit(0);
    }

    private static Window WINDOW;

    private static void edt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void capture(String path) throws Exception {
        edt(() -> {
            BufferedImage image = new BufferedImage(WINDOW.getWidth(), WINDOW.getHeight(), BufferedImage.TYPE_INT_RGB);
            WINDOW.printAll(image.createGraphics());
            try {
                ImageIO.write(image, "png", new File(path));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("校验失败: " + what);
        }
    }
}

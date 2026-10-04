package foragent.tools;

import io.github.preschoolller.agentjava.gui.SettingsDialog;
import io.github.preschoolller.agentjava.gui.Sidebar;
import io.github.preschoolller.agentjava.gui.Window;
import io.github.preschoolller.agentjava.gui.MarkdownRenderer;
import io.github.preschoolller.agentjava.store.AppSettings;
import io.github.preschoolller.agentjava.store.Conversation;
import io.github.preschoolller.agentjava.store.ConversationStore;
import io.github.preschoolller.agentjava.store.JsonKit;
import io.github.preschoolller.agentjava.store.Message;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 前端回归工具（提交在库里，产物落在 gitignore 的 target/ 下）。每次改完 UI 跑一遍：
 * <pre>
 *   mvn -q compile
 *   javac -encoding UTF-8 -cp target/classes -d target/classes foragent/tools/Shot.java
 *   java -cp target/classes foragent.tools.Shot D:/CodingSpace/IdeaProjects/agent-java/target/window
 * </pre>
 * 输出：持久化往返 + 删尾/单条删除/置顶落盘/旧格式迁移测试（PERSIST_OK）、
 * 配置方案往返/重命名/平面键迁移测试（SETTINGS_OK）、Markdown 解析断言（MD_PARSE_OK）、
 * 流式落库断言（STREAM_OK）+ 十一张截图
 * window-{1展开,1b拖宽侧栏,2切换会话,3生成中,4折叠,5设置,6markdown,7流式,8悬停菜单,
 * 9置顶,10搜索高亮,11搜索片段}.png（SHOT_OK）。
 * 注意：Swing 视图树非线程安全，所有 UI 操作必须像下面这样包在 EDT 里执行（见踩坑记录 #1）。
 */
public class Shot {

    /** Markdown 渲染样例：解析断言和 6 号截图共用，覆盖渲染器支持的全部块类型 */
    private static final String SAMPLE_MD = """
            ## 回答要点

            这是 **加粗**贴字、*斜体*贴字、`行内代码`、[链接](https://example.com)、~~删除线~~ 的段落，
            第二行紧跟着上一行，还有裸地址 https://example.com/path?q=1。

            - 无序列表一
            - 无序列表二
                - 嵌套项
            1. 有序列表
            - [x] 已完成任务
            - [ ] 待办任务

            ```java
            public class Demo {
                public static void main(String[] args) {
                    System.out.println("hello 中文注释");
                }
            }
            ```

            > 引用第一行
            > 引用第二行

            ---

            结尾段落。
            """;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("agentjava-verify");

        // —— 持久化往返（含特殊字符）+ 删尾消息，纯模型操作，不涉及 UI ——
        ConversationStore first = new ConversationStore(dir.resolve("history.json"));
        Conversation conv = first.newConversation();
        first.appendMessage(conv.getId(), Conversation.ROLE_USER, "带\"引号\"、\n换行、\t制表和中文✓的消息");
        first.appendMessage(conv.getId(), Conversation.ROLE_AI, "回复");
        first.appendMessage(conv.getId(), Conversation.ROLE_AI, "将被删掉的旧回答");
        expect("将被删掉的旧回答".equals(first.removeLastMessage(conv.getId()).text()), "删尾返回值");
        expect(first.getActive().getMessages().size() == 2, "删尾后消息数");
        first.rename(conv.getId(), "往返测试标题");
        ConversationStore second = new ConversationStore(dir.resolve("history.json"));
        expect(second.getConversations().size() == 1, "会话数");
        expect(second.getActive().getMessages().size() == 2, "消息数");
        expect(second.getActive().getMessages().get(0).text().contains("\n"), "换行保留");
        expect("回复".equals(second.getActive().getMessages().get(1).text()), "删尾后落盘内容");
        expect("往返测试标题".equals(second.getActive().getTitle()), "标题");
        // 单条删除：按消息对象同一性定位，删掉后旧引用失效、新文件只剩一条
        Message middle = second.getActive().getMessages().get(1);
        expect(second.removeMessage(second.getActive().getId(), middle), "按同一性删除");
        expect(second.getActive().getMessages().size() == 1, "删除后消息数");
        expect(!second.removeMessage(second.getActive().getId(), middle), "旧引用重复删除应失败");
        ConversationStore third = new ConversationStore(dir.resolve("history.json"));
        expect(third.getActive().getMessages().size() == 1, "删除后落盘消息数");
        // 置顶落盘（v1 文件格式）：置顶→重读→取消置顶→重读
        first.setPinned(conv.getId(), true);
        ConversationStore fourth = new ConversationStore(dir.resolve("history.json"));
        expect(fourth.getConversations().get(0).isPinned(), "置顶落盘");
        fourth.setPinned(conv.getId(), false);
        expect(!new ConversationStore(dir.resolve("history.json")).getConversations()
                .get(0).isPinned(), "取消置顶落盘");
        // v0 旧格式（顶层数组）迁移读取：字段照常还原，pinned 缺省 false
        Path legacyFile = dir.resolve("legacy.json");
        Files.writeString(legacyFile, JsonKit.write(List.of(Map.of(
                "id", "legacy-1", "title", "旧格式会话", "createdAt", 1L, "updatedAt", 2L,
                "messages", List.of(Map.of("role", "user", "text", "旧消息", "time", 3L))))));
        ConversationStore legacyStore = new ConversationStore(legacyFile);
        expect(legacyStore.getConversations().size() == 1, "旧格式会话数");
        expect("旧格式会话".equals(legacyStore.getConversations().get(0).getTitle()), "旧格式标题");
        expect("旧消息".equals(legacyStore.getConversations().get(0).getMessages().get(0).text()), "旧格式消息");
        expect(!legacyStore.getConversations().get(0).isPinned(), "旧格式默认不置顶");
        System.out.println("PERSIST_OK");

        // —— 配置方案（AppSettings.at 独立实例，不碰真实 ~/.agentjava/settings.properties） ——
        Path settingsFile = dir.resolve("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        expect(testSettings.getProfileNames().equals(List.of("默认")), "首启默认方案");
        testSettings.getActiveProfile().setModel("test-model");
        expect("test-model".equals(testSettings.getModel()), "委托 getter 读当前方案");
        testSettings.addProfile("写作", testSettings.getActiveProfile());
        testSettings.setActiveProfileName("写作");
        testSettings.getActiveProfile().setTemperature(0.3);
        expect(!testSettings.addProfile("写作", testSettings.getActiveProfile()), "重名方案拒绝");
        testSettings.save();
        AppSettings reloaded = AppSettings.at(settingsFile);
        expect(reloaded.getProfileNames().equals(List.of("默认", "写作")), "方案列表往返");
        expect("写作".equals(reloaded.getActiveProfileName()), "活动方案往返");
        expect(reloaded.getActiveProfile().getTemperature() == 0.3, "方案参数往返");
        expect("test-model".equals(reloaded.getProfile("默认").getModel()), "首个方案参数往返");
        reloaded.removeProfile("默认");
        expect(reloaded.getActiveProfileName().equals("写作"), "删活动方案后兜底");
        reloaded.renameProfile("写作", "写作2");
        expect(reloaded.getProfileNames().equals(List.of("写作2")), "重命名");
        // 旧版平面键迁移：backendUrl/model/... 归入"默认"，全局字号保留
        Path legacySettings = dir.resolve("legacy-settings.properties");
        Properties legacyProps = new Properties();
        legacyProps.setProperty("backendUrl", "http://old:8080/chat");
        legacyProps.setProperty("model", "legacy-model");
        legacyProps.setProperty("temperature", "0.5");
        legacyProps.setProperty("contentFontSize", "18");
        try (OutputStream out = Files.newOutputStream(legacySettings)) {
            legacyProps.store(out, "");
        }
        AppSettings migrated = AppSettings.at(legacySettings);
        expect(migrated.getProfileNames().equals(List.of("默认")), "迁移方案数");
        expect("legacy-model".equals(migrated.getActiveProfile().getModel()), "迁移模型");
        expect("http://old:8080/chat".equals(migrated.getActiveProfile().getBackendUrl()), "迁移地址");
        expect(migrated.getActiveProfile().getTemperature() == 0.5, "迁移温度");
        expect(migrated.getContentFontSize() == 18, "全局字号保留");
        System.out.println("SETTINGS_OK");

        // —— Markdown 解析断言（块类型与数量） ——
        List<MarkdownRenderer.Block> md = MarkdownRenderer.parse(SAMPLE_MD);
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.Heading).count() == 1, "标题数");
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.Paragraph).count() == 2, "段落数");
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.ListItem).count() == 6, "列表项数");
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.CodeBlock).count() == 1, "代码块数");
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.Quote).count() == 1, "引用数");
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.Rule).count() == 1, "分隔线数");
        MarkdownRenderer.Paragraph firstPara = (MarkdownRenderer.Paragraph) md.get(1);
        expect(firstPara.spans().stream().anyMatch(s -> s.bold()), "加粗解析");
        expect(firstPara.spans().stream().anyMatch(s -> s.mono()), "行内代码解析");
        expect(firstPara.spans().stream().anyMatch(s -> s.link() != null), "链接解析");
        expect(firstPara.spans().stream().anyMatch(s -> s.strike()), "删除线解析");
        System.out.println("MD_PARSE_OK");

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
        // c3：Markdown 渲染样例（6 号截图）
        Conversation c3 = store.newConversation();
        store.appendMessage(c3.getId(), Conversation.ROLE_USER, "给我演示一下 Markdown 渲染");
        store.appendMessage(c3.getId(), Conversation.ROLE_AI, SAMPLE_MD);

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

        // —— 截图 6：Markdown 渲染（标题/行内样式/代码块/列表/引用/分隔线） ——
        edt(() -> WINDOW.getStore().switchTo(c3.getId()));
        Thread.sleep(1500);
        capture(args[0] + "-6-markdown.png");

        // —— 截图 7：流式输出（appendChunk 节流渲染 + 高度自适应 + 自动滚底） ——
        String streamed = "### 流式输出\n\n这是**逐段**追加的回复，"
                + "每 120ms 渲染一次，`高度`自适应。\n\n```java\nint x = 42;\n```\n";
        Window.AiStream[] streamHolder = new Window.AiStream[1];
        edt(() -> streamHolder[0] = WINDOW.addAiMessage(""));
        Thread.sleep(500);
        edt(() -> streamHolder[0].appendChunk("### 流式输出\n\n这是**逐段**追加的回复，"));
        Thread.sleep(300);
        edt(() -> streamHolder[0].appendChunk("每 120ms 渲染一次，`高度`自适应。\n\n```java\nint x = 42;\n```\n"));
        Thread.sleep(600);
        capture(args[0] + "-7-streaming.png");
        edt(() -> streamHolder[0].finish());
        expect(streamed.equals(store.getActive()
                .getMessages().get(store.getActive().getMessages().size() - 1).text()), "流式落库");
        System.out.println("STREAM_OK");

        // —— 截图 8：悬停"…"菜单（合成 mouseEntered 让浮层显示，不做真实鼠标移动） ——
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof JPanel) {
                    component.dispatchEvent(new MouseEvent(component,
                            MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, 5, 5, 0, false));
                }
            }
        });
        Thread.sleep(400);
        capture(args[0] + "-8-hover.png");

        // —— 截图 9：置顶排序（c2 置顶后跳到列表最前，时间行带"置顶"标签；先把 4 号折叠的侧栏展开回来） ——
        edt(() -> {
            WINDOW.getSidebar().setExpanded(true);
            WINDOW.getSplitPane().setDividerLocation(Sidebar.EXPANDED_WIDTH);
            WINDOW.getContentPane().revalidate();
        });
        Thread.sleep(400);
        edt(() -> WINDOW.getStore().setPinned(c2.getId(), true));
        Thread.sleep(400);
        capture(args[0] + "-9-pinned.png");

        // —— 截图 10：搜索标题命中高亮（"今天"命中 c2 标题） ——
        edt(() -> WINDOW.getSidebar().setSearchQuery("今天"));
        Thread.sleep(400);
        capture(args[0] + "-10-search-highlight.png");

        // —— 截图 11：搜索内容命中片段（"并发"只在 c1 内容里，条目显示片段预览行） ——
        edt(() -> WINDOW.getSidebar().setSearchQuery("并发"));
        Thread.sleep(400);
        capture(args[0] + "-11-search-snippet.png");

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

    /** 深度收集组件树，供合成鼠标事件用 */
    private static void collectComponents(Component component, List<Component> out) {
        out.add(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectComponents(child, out);
            }
        }
    }
}

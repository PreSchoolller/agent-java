package foragent.tools;

import io.github.preschoolller.agentjava.frontend.gui.SettingsDialog;
import io.github.preschoolller.agentjava.frontend.gui.Sidebar;
import io.github.preschoolller.agentjava.frontend.gui.Window;
import io.github.preschoolller.agentjava.frontend.gui.MarkdownRenderer;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import io.github.preschoolller.agentjava.frontend.store.Conversation;
import io.github.preschoolller.agentjava.frontend.store.ConversationStore;
import io.github.preschoolller.agentjava.frontend.store.JsonKit;
import io.github.preschoolller.agentjava.frontend.store.Message;
import io.github.preschoolller.agentjava.frontend.store.ModelMeta;

import javax.imageio.ImageIO;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
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
 * （3d1d03d 曾因前端 store 引 backend.constant.Constants 连带 Jackson 进类路径而临时改用
 *  dependency:build-classpath 全拼依赖；ad68322 把 MAPPER 收进惰性 holder 后，加载 Constants
 *  不再初始化 Jackson，已复归裸 target/classes——详见踩坑记录 #26 补注）
 * 输出：持久化往返 + 删尾/分叉/置顶/工作区落盘/主文件元数据化（PERSIST_OK）、
 * 按需加载断言（LAZY_OK：新建 store 不读会话文件、元数据保存不重写会话文件、触达消息才读）、
 * 配置方案往返/重命名/平面键迁移/主题偏好测试（SETTINGS_OK）、
 * Markdown 解析断言（MD_PARSE_OK）、
 * 设置面板分页切换断言（默认页/双向往返 + 5b 号通用页截图）、
 * 流式落库断言（STREAM_OK）、工具调用卡断言（TOOLCARD_OK）、权限确认卡断言（APPROVAL_OK）、
 * 长会话窗口化断言（WINDOWING_OK）+ 工作区弹窗 + 侧栏工作区分组与组头加号（WORKSPACE_OK，
 * 主区工作区显示条已移除，分组头是工作区唯一可见处）+
 * 窄窗口工具审批/摘要不能裁掉一个按钮、展开能看见参数全文（NARROW_OK）+
 * 输入框高度自适应增高/触帽内部滚动/清空回落（INPUT_OK）+
 * 二十四张截图
 * window-{1展开,1b拖宽侧栏,2切换会话,3生成中,4折叠,5设置(5b通用页/5c目录展开),6markdown,7流式,8悬停菜单,
 * 9置顶,10搜索高亮,11搜索片段,12右键菜单,13长会话,14深色主题,15深色设置,16工具卡片,
 * 16b工具卡展开,17权限确认卡,18划词选区,19会话内搜索,20工作区弹窗,20b分组加号,21输入增高}.png（SHOT_OK）。 * 注意：Swing 视图树非线程安全，所有 UI 操作必须像下面这样包在 EDT 里执行（见踩坑记录 #1）。
 */
public class Shot {

    /** Markdown 渲染样例：解析断言和 6 号截图共用，覆盖渲染器支持的全部块类型 */
    private static final String SAMPLE_MD = """
            ## 回答要点

            这是 **加粗**贴字、*斜体*贴字、`行内代码`、[链接](https://example.com)、~~删除线~~ 的段落，
            第二行紧跟着上一行，还有裸地址 https://example.com/path?q=1。

            工具一览：

            | 工具 | 权限 | 说明 |
            | --- | --- | --- |
            | read | 只读 | 读取文本文件内容 |
            | write | 需确认 | 写入文件前经用户放行 |

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

            文件修改 diff：

            ```diff
            --- a/src/Demo.java
            +++ b/src/Demo.java
            @@ -12,7 +12,9 @@
             public class Demo {
            -    private static final int SIZE = 10;
            +    private static final int SIZE = 20;
                 private Buffer buffer;
             }
            ```

            结尾段落。
            """;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("SHOT_FAILED");
        } finally {
            System.exit(0); // 失败也要退干净：AWT 线程不退会留下还能交互的僵尸窗口误导排查
        }
    }

    private static void run(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("agentjava-verify");
        // 截图配色固定浅色：开发者本机存过深色时回归图才有确定基线（只改内存，不落盘）
        AppSettings.get().setUiTheme("light");

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
        // 分叉：从 AI 回复分叉出新会话（含这条回复，从该状态继续），原会话不动，当前会话切到分叉
        Message forkPoint = second.getActive().getMessages().get(1);
        Conversation forked = second.fork(second.getActive().getId(), forkPoint);
        expect(forked != null, "分叉返回新会话");
        expect(second.getActive().getId().equals(forked.getId()), "分叉后切到新会话");
        expect(forked.getMessages().size() == 2, "分叉会话消息数");
        expect("带\"引号\"、\n换行、\t制表和中文✓的消息".equals(forked.getMessages().get(0).text()), "分叉复制用户轮");
        expect("回复".equals(forked.getMessages().get(1).text()), "分叉含分叉点AI回复");
        expect("往返测试标题（分叉）".equals(forked.getTitle()), "分叉标题带来源");
        expect(second.fork(forked.getId(), forked.getMessages().get(0)) == null, "用户消息不可分叉");
        expect(second.getConversations().get(1).getMessages().size() == 2, "原会话不动");
        ConversationStore third = new ConversationStore(dir.resolve("history.json"));
        expect(third.getConversations().size() == 2, "分叉落盘会话数");
        expect(third.getActive().getId().equals(forked.getId()), "分叉后活跃会话落盘");
        // 主文件只存元数据：messages 字段已移除，消息全部由 sessions/<会话id> 单文件承载
        Object mainRoot = JsonKit.parse(Files.readString(dir.resolve("history.json")));
        for (Object item : (List<?>) ((Map<?, ?>) mainRoot).get("conversations")) {
            expect(!((Map<?, ?>) item).containsKey("messages"), "主文件不含messages字段");
        }
        Object sessionRoot = JsonKit.parse(Files.readString(dir.resolve("sessions").resolve(conv.getId())));
        expect(sessionRoot instanceof List<?> sessionList && sessionList.size() == 2
                && "回复".equals(((Map<?, ?>) sessionList.get(1)).get("text")), "单会话文件承载消息");
        // 置顶落盘（v1 文件格式）：置顶→重读→取消置顶→重读（分叉后列表首位是新会话，按 id 找原会话）
        first.setPinned(conv.getId(), true);
        ConversationStore fourth = new ConversationStore(dir.resolve("history.json"));
        expect(pinnedOf(fourth, conv.getId()), "置顶落盘");
        fourth.setPinned(conv.getId(), false);
        expect(!pinnedOf(new ConversationStore(dir.resolve("history.json")), conv.getId()), "取消置顶落盘");
        // 删空活跃会话（重新生成走 removeLastMessage 删到最后一条）：主文件不再落盘，残留单文件清掉
        ConversationStore fifth = new ConversationStore(dir.resolve("history.json"));
        String fifthId = fifth.getActive().getId();
        fifth.appendMessage(fifthId, Conversation.ROLE_USER, "将删空的消息");
        while (fifth.removeLastMessage(fifthId) != null) {
        }
        expect(Files.notExists(dir.resolve("sessions").resolve(fifthId)), "删空会话清残留单文件");
        // 崩溃恢复（P0-5）：落盘的 running 工具消息（程序中途崩了/被杀的残留），重载后翻成"已中断"
        Path crashDir = Files.createTempDirectory("agentjava-crash");
        ConversationStore crashFirst = new ConversationStore(crashDir.resolve("history.json"));
        String crashId = crashFirst.newConversation().getId();
        crashFirst.appendToolCall(crashId, "read_file", "{}", "call_crash");
        Message crashTool = new ConversationStore(crashDir.resolve("history.json"))
                .getActive().getMessages().get(0);
        expect(crashTool.isToolCall() && "interrupted".equals(crashTool.toolStatus()),
                "崩溃残留 running 翻成已中断");
        // 工作区（计划外）：会话绑定目录落盘往返；分叉沿用；默认（null）不写键；空会话复用时改绑
        String wsPath = dir.resolve("ws-project").toString();
        first.newConversation(wsPath);
        first.appendMessage(first.getActive().getId(), Conversation.ROLE_USER, "工作区测试");
        first.appendMessage(first.getActive().getId(), Conversation.ROLE_AI, "好");
        Conversation wsForked = first.fork(first.getActive().getId(),
                first.getActive().getMessages().get(1));
        expect(wsPath.equals(wsForked.getWorkspace()), "分叉沿用工作区");
        ConversationStore wsReread = new ConversationStore(dir.resolve("history.json"));
        expect(wsReread.getConversations().stream()
                .anyMatch(conversation -> wsPath.equals(conversation.getWorkspace())), "工作区落盘往返");
        Path wsDefaultDir = Files.createTempDirectory("agentjava-ws");
        ConversationStore wsDefaultStore = new ConversationStore(wsDefaultDir.resolve("history.json"));
        Conversation defaultConv = wsDefaultStore.newConversation();
        expect(defaultConv.getWorkspace() == null, "默认工作区为 null");
        wsDefaultStore.appendMessage(defaultConv.getId(), Conversation.ROLE_USER, "x");
        expect(new ConversationStore(wsDefaultDir.resolve("history.json"))
                .getActive().getWorkspace() == null, "默认工作区不落盘");
        // 空会话复用时改绑：当前是没说过话的空会话，再 new 一次直接改工作区而不是新建
        ConversationStore reuseStore = new ConversationStore(
                Files.createTempDirectory("agentjava-reuse").resolve("history.json"));
        Conversation firstEmpty = reuseStore.newConversation();
        Conversation reusedConv = reuseStore.newConversation(wsPath);
        expect(reusedConv.getId().equals(firstEmpty.getId()), "空会话复用");
        expect(wsPath.equals(reusedConv.getWorkspace()), "复用空会话改绑工作区");
        System.out.println("PERSIST_OK");
        // 按需加载：启动只读索引，会话文件延迟到首次触达消息才读；未加载的会话被改元数据时
        // 也不重写会话文件（mtime 不变），触碰消息后仍拿得到磁盘内容
        Path lazyDir = Files.createTempDirectory("agentjava-lazy");
        ConversationStore lazyFirst = new ConversationStore(lazyDir.resolve("history.json"));
        String lazyId = lazyFirst.newConversation().getId();
        lazyFirst.appendMessage(lazyId, Conversation.ROLE_USER, "按需加载的旧消息");
        Path lazySession = lazyDir.resolve("sessions").resolve(lazyId);
        long lazyMtime = Files.getLastModifiedTime(lazySession).toMillis();
        Thread.sleep(30); // 会话文件先落盘，稍后靠 mtime 判断有没有被多余重写
        ConversationStore lazySecond = new ConversationStore(lazyDir.resolve("history.json"));
        lazySecond.rename(lazySecond.getConversations().get(0).getId(), "改名但没读消息");
        expect(Files.getLastModifiedTime(lazySession).toMillis() == lazyMtime, "元数据保存不重写会话文件");
        expect(lazySecond.getActive().getMessages().size() == 1
                && "按需加载的旧消息".equals(lazySecond.getActive().getMessages().get(0).text()),
                "首次触达时才读消息");
        System.out.println("LAZY_OK");

        // —— 配置方案（AppSettings.at 独立实例，不碰真实 ~/.agentjava/settings.properties） ——
        Path settingsFile = dir.resolve("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        expect(testSettings.getProfileNames().equals(List.of("默认")), "首启默认方案");
        testSettings.getActiveProfile().setModel("test-model");
        expect("test-model".equals(testSettings.getModel()), "委托 getter 读当前方案");
        // 可选模型列表：设置→落盘→重读→默认项不在列表里时落到第一个
        testSettings.getActiveProfile().setModels(List.of("model-a", "model-b"));
        testSettings.getActiveProfile().setModel("model-b");
        expect(testSettings.getActiveProfile().getModel().equals("model-b"), "默认项在列表里保持");
        testSettings.getActiveProfile().setModels(List.of("model-c"));
        expect(testSettings.getActiveProfile().getModel().equals("model-c"), "默认项不在列表里落到第一个");
        testSettings.getActiveProfile().setModels(List.of("model-a", "model-b"));
        testSettings.getActiveProfile().setModel("model-b");
        // 模型目录元数据（计划外）：名字/上下文/最大输出/推理等级/输入能力整块落盘往返
        ModelMeta richA = new ModelMeta("model-a");
        richA.setCtxWindow("256K");
        richA.setMaxOutput("32K");
        richA.setReasoning("HIGH");
        richA.setText(true);
        ModelMeta richB = new ModelMeta("model-b");
        richB.setImage(true);
        testSettings.getActiveProfile().setModelMetas(List.of(richA, richB));
        testSettings.addProfile("写作", testSettings.getActiveProfile());
        testSettings.setActiveProfileName("写作");
        testSettings.getActiveProfile().setTemperature(0.3);
        testSettings.getActiveProfile().setApiType("chatcompletion");
        expect(!testSettings.addProfile("写作", testSettings.getActiveProfile()), "重名方案拒绝");
        testSettings.save();
        AppSettings reloaded = AppSettings.at(settingsFile);
        expect(reloaded.getProfileNames().equals(List.of("默认", "写作")), "方案列表往返");
        expect("写作".equals(reloaded.getActiveProfileName()), "活动方案往返");
        expect(reloaded.getActiveProfile().getTemperature() == 0.3, "方案参数往返");
        expect("model-b".equals(reloaded.getProfile("默认").getModel()), "首个方案参数往返");
        expect(reloaded.getProfile("写作").getModels().equals(List.of("model-a", "model-b")), "模型列表往返");
        expect(reloaded.getProfile("写作").getModel().equals("model-b"), "默认模型往返");
        ModelMeta savedA = reloaded.getProfile("写作").getModelMetas().get(0);
        expect("model-a".equals(savedA.getName()) && "256K".equals(savedA.getCtxWindow())
                && "32K".equals(savedA.getMaxOutput()) && "HIGH".equals(savedA.getReasoning())
                && savedA.isText() && !savedA.isImage(), "模型元数据往返");
        expect(reloaded.getProfile("写作").getModelMetas().get(1).isImage(), "模型图片能力往返");
        expect("HIGH".equals(testSettings.getModelMeta("model-a").normalizedReasoning())
                && testSettings.getModelMeta("不存在") == null
                && testSettings.getModelMeta(null) == null, "模型元数据查找与非法推理等级回落");
        // API 类型（计划外，后端待支持）：写值往返；未设置的方案与旧文件缺键都是默认 response
        expect("chatcompletion".equals(reloaded.getProfile("写作").getApiType()), "API 类型往返");
        expect("response".equals(reloaded.getProfile("默认").getApiType()), "未设置方案默认 response");
        reloaded.getProfile("写作").setApiType("bogus");
        expect("response".equals(reloaded.getProfile("写作").getApiType()), "非法 API 类型回落 response");
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
        // models 键缺失（旧文件）时用 model 单值兜底成单元素列表
        expect(migrated.getActiveProfile().getModels().equals(List.of("legacy-model")), "旧格式模型列表兜底");
        // modelsMeta 缺键但 models 逗号键在（上一版格式）回落：名字照收，元数据落默认值
        Path legacyModels = dir.resolve("legacy-models.properties");
        Properties legacyModelsProps = new Properties();
        legacyModelsProps.setProperty("profileNames", "默认");
        legacyModelsProps.setProperty("activeProfile", "默认");
        legacyModelsProps.setProperty("profile.默认.model", "old-a");
        legacyModelsProps.setProperty("profile.默认.models", "old-a,old-b");
        try (OutputStream out = Files.newOutputStream(legacyModels)) {
            legacyModelsProps.store(out, "");
        }
        AppSettings legacyMigrated = AppSettings.at(legacyModels);
        expect(legacyMigrated.getModels().equals(List.of("old-a", "old-b")), "旧逗号键模型列表回落");
        expect(!legacyMigrated.getModelMeta("old-b").isImage(), "缺元数据条目参数落默认值");
        // apiType 键缺失（旧文件）时落默认 response
        expect("response".equals(migrated.getActiveProfile().getApiType()), "旧格式 API 类型默认");
        // 主题偏好：默认浅色、非法值忽略、跟随系统档、落盘往返
        expect("light".equals(testSettings.getUiTheme()), "主题默认浅色");
        testSettings.setUiTheme("深色");
        expect("light".equals(testSettings.getUiTheme()), "非法主题值忽略");
        testSettings.setUiTheme("system");
        expect("system".equals(testSettings.getUiTheme()), "跟随系统档落值");
        testSettings.setUiTheme("dark");
        testSettings.save();
        expect("dark".equals(AppSettings.at(settingsFile).getUiTheme()), "主题落盘往返");
        // 日志等级：默认 info、非法值忽略、落盘往返（TinyLog 重启读一次，界面报价见 5 号块 tooltip）
        expect("info".equals(testSettings.getLogLevel()), "日志等级默认 info");
        testSettings.setLogLevel("恐怖");
        expect("info".equals(testSettings.getLogLevel()), "非法日志等级忽略");
        testSettings.setLogLevel("debug");
        testSettings.save();
        expect("debug".equals(AppSettings.at(settingsFile).getLogLevel()), "日志等级落盘往返");
        // resolveTheme：system 档探测后必落在两套主题之一（Windows 读注册表，失败按浅色）
        expect(java.util.Set.of("light", "dark").contains(testSettings.resolvedUiTheme()), "系统主题解析收敛");
        expect(!testSettings.isMinimizeToTray(), "托盘偏好默认关闭");
        testSettings.setMinimizeToTray(true);
        testSettings.save();
        expect(AppSettings.at(settingsFile).isMinimizeToTray(), "托盘偏好落盘往返");
        System.out.println("SETTINGS_OK");

        // —— Markdown 解析断言（块类型与数量） ——
        List<MarkdownRenderer.Block> md = MarkdownRenderer.parse(SAMPLE_MD);
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.Heading).count() == 1, "标题数");
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.Paragraph).count() == 4, "段落数");
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.ListItem).count() == 6, "列表项数");
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.CodeBlock).count() == 2, "代码块数");
        // diff 块：内容原样进 CodeBlock（渲染层按行着色），增行删行都在
        MarkdownRenderer.CodeBlock diffBlock = md.stream()
                .filter(b -> b instanceof MarkdownRenderer.CodeBlock c && "diff".equals(c.language()))
                .map(b -> (MarkdownRenderer.CodeBlock) b).findFirst().orElseThrow();
        expect(diffBlock.code().lines().count() == 8, "diff 行数");
        expect(diffBlock.code().contains("-    private static final int SIZE = 10;")
                && diffBlock.code().contains("+    private static final int SIZE = 20;"), "diff 增删行原样");
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.Quote).count() == 1, "引用数");
        expect(md.stream().filter(b -> b instanceof MarkdownRenderer.Rule).count() == 1, "分隔线数");
        // 表格（P1-5）：分隔行识别、单元格内容、转义竖线、表头夹在段落里也能接管
        List<MarkdownRenderer.Block> tableOnly = md.stream()
                .filter(b -> b instanceof MarkdownRenderer.TableBlock).toList();
        expect(tableOnly.size() == 1, "表格块数");
        MarkdownRenderer.TableBlock table = (MarkdownRenderer.TableBlock) tableOnly.get(0);
        expect(table.rows().size() == 3
                && "工具".equals(table.rows().get(0).get(0).get(0).text())
                && "读取文本文件内容".equals(table.rows().get(1).get(2).get(0).text())
                && "write".equals(table.rows().get(2).get(0).get(0).text()), "表头/体行单元格");
        List<MarkdownRenderer.Block> notTable = MarkdownRenderer.parse("没有分隔行不算表格\n| a | b |\n| --- | --- |");
        expect(notTable.get(0) instanceof MarkdownRenderer.Paragraph, "无分隔行的孤立段落不成表");
        expect(notTable.get(1) instanceof MarkdownRenderer.TableBlock, "段落后的表头能接管成表");
        List<MarkdownRenderer.Block> escaped = MarkdownRenderer.parse("| a \\| b | c |\n| --- | --- |");
        expect(((MarkdownRenderer.TableBlock) escaped.get(0)).rows().get(0).get(0).get(0).text().equals("a | b"),
                "转义竖线");
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
            // 尺寸/位置由 Window 按 AppSettings 恢复（不强制 1280×720，避免把回归用的尺寸写回用户设置）
            window.setVisible(true);
            WINDOW = window;
        });
        Thread.sleep(1500);
        // 输入区右下角控件：模型/思考强度默认选中项非空
        edt(() -> {
            expect(WINDOW.getSelectedModel() != null, "输入区默认模型非空");
            expect(WINDOW.getSelectedEffort() != null, "输入区默认思考强度非空");
            expect(WINDOW.getSelectedMode() != null, "输入区默认权限模式非空");
        });
        // 侧栏按工作区分组：回归用会话都没绑工作区 → 单一"默认工作区"组头；主区工作区显示条已移除
        expect(sidebarGroupRows().stream().anyMatch(g -> "默认工作区".equals(g[0]) && g[1] == null),
                "侧栏默认工作区分组在");
        expect(!uiHasText("复制路径"), "主区工作区显示条已移除（复制路径钮随条消失）");
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
        expect(sidebarBadgeHtml(WINDOW.getStore().getActive().getId()).contains(">生成中<"),
                "侧栏亮\"生成中\"标注");
        edt(() -> WINDOW.setGenerating(false));
        expect(!sidebarBadgeHtml(WINDOW.getStore().getActive().getId()).contains(">生成中<"),
                "生成结束侧栏标注熄灭");

        // —— 截图 4：折叠侧栏 ——
        edt(() -> {
            WINDOW.getSidebar().setExpanded(false);
            WINDOW.getSplitPane().setDividerLocation(Sidebar.COLLAPSED_WIDTH);
            WINDOW.getContentPane().revalidate();
        });
        Thread.sleep(800);
        capture(args[0] + "-4-collapsed.png");

        // —— 截图 5：设置面板 ——
        // 模型目录先钉成两条（内存改动不落盘，同 127 行主题同款）：行删除钮"打开即可用"
        //（目录>1 的规则接替原模型列表删除钮）与展开参数区断言依赖确定形状——
        // 曾经 modelRow 写死 setEnabled(false) 把初始可用态按灭（踩坑 #25），Shot 钉死
        List<ModelMeta> oldMetas = AppSettings.get().getActiveProfile().getModelMetas();
        String oldModel = AppSettings.get().getActiveProfile().getModel();
        ModelMeta shotA = new ModelMeta("model-a");
        shotA.setCtxWindow("256K");
        shotA.setMaxOutput("32K");
        shotA.setReasoning("HIGH");
        ModelMeta shotB = new ModelMeta("model-b");
        shotB.setImage(true);
        AppSettings.get().getActiveProfile().setModelMetas(List.of(shotA, shotB));
        AppSettings.get().getActiveProfile().setModel("model-a");
        SettingsDialog[] dialogHolder = new SettingsDialog[1];
        edt(() -> {
            SettingsDialog dialog = new SettingsDialog(WINDOW, false);
            dialog.setVisible(true);
            dialogHolder[0] = dialog;
        });
        Thread.sleep(600);
        boolean[] modelDeleteEnabled = new boolean[1];
        edt(() -> modelDeleteEnabled[0] = dialogHolder[0].isModelRowDeleteEnabled(0));
        expect(modelDeleteEnabled[0], "打开设置行删除钮即可用（目录两条）");
        edt(() -> {
            SettingsDialog dialog = dialogHolder[0];
            BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
            dialog.printAll(image.createGraphics());
            try {
                ImageIO.write(image, "png", new File(args[0] + "-5-settings.png"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // —— 设置面板分页断言（计划外）：默认页=模型服务；两枚胶囊来回切，各自页内控件在/不在。
        //  用 uiHasShowingTextAnyWindow：CardLayout 隐藏页的组件仍在容器树里，不带显隐过滤会误报 ——
        expect(uiHasShowingTextAnyWindow("配置方案") && uiHasShowingTextAnyWindow("API 类型"), "默认页=模型服务页");
        expect(!uiHasShowingTextAnyWindow("正文字号"), "模型服务页不含全局项");
        edt(() -> uiClickAnyWindow("通用"));
        Thread.sleep(400);
        expect(uiHasShowingTextAnyWindow("正文字号") && uiHasShowingTextAnyWindow("主题")
                && uiHasShowingTextAnyWindow("日志等级"), "切到通用页（全局项在）");
        expect(!uiHasShowingTextAnyWindow("配置方案"), "通用页不含配置方案控件");
        captureDialog(args[0] + "-5b-settings-general.png", "设置");
        edt(() -> uiClickAnyWindow("模型服务"));
        Thread.sleep(400);
        expect(uiHasShowingTextAnyWindow("配置方案"), "切回模型服务页");
        // 展开首条：参数区出现（收起态只有名字行）——目录的参数行随"展开/收起"钮切换
        edt(() -> uiClickAnyWindow("展开"));
        Thread.sleep(300);
        expect(uiHasShowingTextAnyWindow("上下文") && uiHasShowingTextAnyWindow("最大输出"),
                "展开后上下文/最大输出在");
        expect(uiHasShowingTextAnyWindow("推理等级") && uiHasShowingTextAnyWindow("文本")
                && uiHasShowingTextAnyWindow("图片"), "展开后推理等级/输入能力在");
        edt(() -> {
            SettingsDialog dialog = dialogHolder[0];
            BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
            dialog.printAll(image.createGraphics());
            try {
                ImageIO.write(image, "png", new File(args[0] + "-5c-settings-catalog.png"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        edt(() -> dialogHolder[0].dispose());
        // 还原模型目录（改动只在内存，给后续深色/托盘段留原样）
        AppSettings.get().getActiveProfile().setModelMetas(oldMetas);
        AppSettings.get().getActiveProfile().setModel(oldModel);

        // —— 截图 6：Markdown 渲染（标题/行内样式/代码块/列表/引用/分隔线/表格） ——
        // 切会话会自动滚到底，先滚回顶部让卡片开头的标题入镜
        edt(() -> WINDOW.getStore().switchTo(c3.getId()));
        Thread.sleep(1500);
        scrollWaterfallToTop();
        Thread.sleep(400);
        capture(args[0] + "-6-markdown.png");
        // 用户消息复制（用户卡尾部"复制"钮）：uiClick 按树序先碰用户卡，剪贴板应是用户原文
        edt(() -> uiClick("复制"));
        Thread.sleep(200);
        expect("给我演示一下 Markdown 渲染".equals(clipboardText()), "用户消息复制到剪贴板");
        // 链接跳转（P1-6）：显示中正文 pane 的链接字符带 url 属性，modelToView 反推坐标能命中
        expect(uiLinkHit("https://example.com/path?q=1"), "链接字符带 url 属性且命中检测可取回");
        // 划词复制（计划外）：显示组件可聚焦可选中，Ctrl+C 拷选区而非整卡
        selectAndCtrlC("给我演示一下 Markdown 渲染", 0, 4);
        Thread.sleep(400); // 选区重绘是异步的，等一拍再截，不然截图里没有高亮
        capture(args[0] + "-18-selection.png");
        Thread.sleep(200);
        expect("给我演示".equals(clipboardText()), "划词 Ctrl+C 复制选区");
        // 会话内搜索（Ctrl+F）：开搜索条填词，匹配计数与定位跳转；收起后入口消失
        edt(() -> WINDOW.openSearch("Markdown"));
        Thread.sleep(400);
        capture(args[0] + "-19-search.png");
        expect(uiHasShowingText("上一个") && uiHasShowingText("下一个") && uiHasShowingText("收起"), "搜索条打开");
        expect(uiHasLabelContaining(" / "), "命中计数与游标显示");
        edt(() -> WINDOW.closeSearch());
        Thread.sleep(300);
        expect(!uiHasShowingText("收起"), "搜索条收起");

        // —— 截图 7：流式输出（appendChunk/appendThinking 双通道节流渲染 + 独立思考区 + 自动滚底） ——
        // 思考走独立通道：流式中独立思考区展开增长，收尾后收起显示时长；正文全程无思考标记拼接
        String streamedBody = "### 流式输出\n\n这是**逐段**追加的回复，"
                + "每 120ms 渲染一次，`高度`自适应。\n\n```java\nint x = 42;\n```\n";
        Window.AiStream[] streamHolder = new Window.AiStream[1];
        edt(() -> streamHolder[0] = WINDOW.addAiMessage(""));
        Thread.sleep(500);
        edt(() -> streamHolder[0].appendThinking("先拆解一下问题再作答。\n思考区与正文各走各的缓冲。\n"));
        Thread.sleep(300);
        expect(uiHasTextPaneContaining("先拆解一下问题再作答"), "思考增量直接进独立思考区，思考中展开可见");
        // 乱序容忍：思考没收尾正文先到，思考还能继续追加，两边互不干扰
        edt(() -> streamHolder[0].appendChunk("### 流式输出\n\n这是**逐段**追加的回复，"));
        edt(() -> streamHolder[0].appendThinking("正文先到，思考继续追加。\n"));
        Thread.sleep(300);
        edt(() -> streamHolder[0].finishThinking());
        Thread.sleep(300);
        expect(uiHasLabelContaining("已思考") && uiHasLabelContaining("秒"), "思考收尾收起为一行并显示时长");
        edt(() -> uiClickNamed("thinking-header"));
        Thread.sleep(300);
        expect(uiHasTextPaneContaining("先拆解一下问题再作答"), "点头部展开已收起的思考独立区");
        edt(() -> uiClickNamed("thinking-header"));
        Thread.sleep(300);
        expect(!uiHasTextPaneContaining("先拆解一下问题再作答"), "再点收回");
        edt(() -> streamHolder[0].appendChunk("每 120ms 渲染一次，`高度`自适应。\n\n```java\nint x = 42;\n```\n"));
        Thread.sleep(600);
        capture(args[0] + "-7-streaming.png");
        edt(() -> streamHolder[0].finish());
        Message streamedMessage = store.getActive()
                .getMessages().get(store.getActive().getMessages().size() - 1);
        expect(streamedBody.equals(streamedMessage.text()), "正文落库干净（无思考标记拼接）");
        expect(streamedMessage.thinking() != null
                        && streamedMessage.thinking().contains("先拆解一下问题再作答")
                        && streamedMessage.thinking().contains("正文先到，思考继续追加"),
                "思考独立落库（含乱序后到的那一段）");
        expect(!streamedMessage.text().contains("拆解"), "正文字段不含思考文本（混杂问题消除）");
        Message reloadedStreamed = new ConversationStore(dir.resolve("shot.json"))
                .getActive().getMessages().get(store.getActive().getMessages().size() - 1);
        expect(reloadedStreamed.thinking() != null
                        && reloadedStreamed.thinking().contains("先拆解")
                        && streamedBody.equals(reloadedStreamed.text()),
                "正文与思考落盘往返");

        // —— 异常收尾：只吐了思考就断流（后端报错 / 用户点停止），正文永远不到 ——
        // 契约：finish() 必须兜底收尾思考区——收成"已思考 N 秒"一行，不能永远停在"思考中…"
        Window.AiStream[] brokenHolder = new Window.AiStream[1];
        edt(() -> brokenHolder[0] = WINDOW.addAiMessage(""));
        Thread.sleep(300);
        edt(() -> brokenHolder[0].appendThinking("只吐了思考，正文还没到就断流了。\n"));
        Thread.sleep(300);
        expect(uiHasLabelContaining("思考中"), "断流前思考区处于展开的思考中态");
        edt(() -> brokenHolder[0].finish()); // 等价于 Main 的 finally 分支，正文为空
        Thread.sleep(300);
        expect(uiHasLabelContaining("已思考") && uiHasLabelContaining("秒"),
                "断流后 finish() 兜底把思考区收成已思考一行（不会永停思考中）");
        expect(!uiHasLabelContaining("思考中"), "收尾后不再显示思考中");
        Message brokenMessage = store.getActive()
                .getMessages().get(store.getActive().getMessages().size() - 1);
        expect("".equals(brokenMessage.text()) && brokenMessage.thinking() != null
                        && brokenMessage.thinking().contains("正文还没到就断流"),
                "空正文 + 思考独立落库");


        System.out.println("STREAM_OK");

        // —— agent 工具调用卡（契约：addToolCall(名, 参数摘要) → appendArgs(增量) 流式显示 → finish(结果, 是否成功, 完整参数)） ——
        // 截图 16：一张运行中只显示了一半参数 + 一张完成；跑完断言落库与落盘往返
        Conversation[] toolConvHolder = new Conversation[1];
        edt(() -> toolConvHolder[0] = store.newConversation());
        Conversation cTool = toolConvHolder[0];
        Thread.sleep(1000); // fireChanged 已把主区切到新会话
        Window.ToolCallHandle[] toolHolder = new Window.ToolCallHandle[1];
        edt(() -> toolHolder[0] = WINDOW.addToolCall("read_file", "", "call_2217929885810011"));
        edt(() -> toolHolder[0].appendArgs("{\"path\": "));
        Thread.sleep(200);
        edt(() -> toolHolder[0].appendArgs("\"src/Demo."));
        Thread.sleep(200);
        capture(args[0] + "-16-tool-card.png"); // "运行中…"状态，参数已流式显示出一半
        edt(() -> toolHolder[0].appendArgs("java\"}"));
        Thread.sleep(200);
        // finish 传空参数：终态参数应回退到流式累积的完整 JSON（argsByCallId 兜底路径）
        edt(() -> toolHolder[0].finish("public class Demo {\n    private Buffer buffer;\n}", true, ""));
        edt(() -> {
            Window.ToolCallHandle handle = WINDOW.addToolCall("read_file", "{\"path\": \"missing.txt\"}", "call_2217929885810012");
            handle.finish("错误: 文件不存在", false, "{\"path\": \"missing.txt\"}");
        });
        Thread.sleep(400);
        expect(store.getActive().getMessages().size() == 2, "工具卡消息数");
        expect(store.getActive().getMessages().get(0).isToolCall()
                && "ok".equals(store.getActive().getMessages().get(0).toolStatus())
                && store.getActive().getMessages().get(0).text().contains("Demo"), "工具结果落库");
        expect("{\"path\": \"src/Demo.java\"}".equals(store.getActive().getMessages().get(0).toolArgs()),
                "流式参数累积后 finish 空参回退落库");
        expect("error".equals(store.getActive().getMessages().get(1).toolStatus()), "失败态落库");
        ConversationStore toolReload = new ConversationStore(dir.resolve("shot.json"));
        Message reloadedTool = toolReload.getActive().getMessages().get(0);
        expect(reloadedTool.isToolCall() && "read_file".equals(reloadedTool.toolName())
                && "{\"path\": \"src/Demo.java\"}".equals(reloadedTool.toolArgs())
                && "ok".equals(reloadedTool.toolStatus()), "工具消息落盘往返");
        // 工具卡折叠（P1-4）：默认收成一行，点头部展开出结果与复制入口，再点收回
        expect(!uiHasTextAreaStartingWith("public class Demo"), "工具卡默认折叠");
        edt(() -> uiClickNamed("tool-header"));
        Thread.sleep(300);
        expect(uiHasTextAreaStartingWith("public class Demo"), "点头部展开结果");
        expect(uiHasText("复制结果") && uiHasText("复制参数"), "展开后有复制入口");
        capture(args[0] + "-16b-tool-expanded.png");
        edt(() -> uiClickNamed("tool-header"));
        Thread.sleep(300);
        expect(!uiHasTextAreaStartingWith("public class Demo"), "再点头部收回");
        System.out.println("TOOLCARD_OK");

        // —— 权限确认卡（P0-4 契约：requestApproval(future) → 卡片"待确认"+允许/拒绝 → 点击写 future 并回到运行中） ——
        // 截图 17：待确认卡。待确认是纯显示态不落库，断言看视图树与 future，落库断言看决完之后的终态
        Window.ToolCallHandle[] approvalHolder = new Window.ToolCallHandle[1];
        edt(() -> approvalHolder[0] = WINDOW.addToolCall("write_file", "{\"path\": \"a.txt\"}", "call_approval_1"));
        java.util.concurrent.CompletableFuture<Boolean>[] decision = new java.util.concurrent.CompletableFuture[1];
        edt(() -> {
            decision[0] = new java.util.concurrent.CompletableFuture<>();
            approvalHolder[0].requestApproval(decision[0]);
        });
        Thread.sleep(300);
        expect(uiHasText("待确认") && uiHasText("允许") && uiHasText("拒绝"), "待确认态徽标与按钮");
        expect(sidebarBadgeHtml(WINDOW.getStore().getActive().getId()).contains(">待确认<"),
                "侧栏亮\"待确认\"标注");
        capture(args[0] + "-17-approval.png");
        edt(() -> uiClick("允许"));
        Thread.sleep(300);
        expect(Boolean.TRUE.equals(decision[0].getNow(null)), "允许按钮写入 future");
        expect(!sidebarBadgeHtml(WINDOW.getStore().getActive().getId()).contains(">待确认<"),
                "审批决出侧栏标注熄灭");
        expect(!uiHasText("待确认") && !uiHasText("允许") && uiHasText("运行中…"), "放行后回到运行中态");
        edt(() -> approvalHolder[0].finish("写入完成", true, "{\"path\": \"a.txt\"}"));
        // 拒绝路径：按钮写 false，随后按 provider 的收尾（DENIED_RESULT, 失败）落库
        Window.ToolCallHandle[] denyHolder = new Window.ToolCallHandle[1];
        java.util.concurrent.CompletableFuture<Boolean>[] denied = new java.util.concurrent.CompletableFuture[1];
        edt(() -> {
            denyHolder[0] = WINDOW.addToolCall("write_file", "{\"path\": \"b.txt\"}", "call_approval_2");
            denied[0] = new java.util.concurrent.CompletableFuture<>();
            denyHolder[0].requestApproval(denied[0]);
        });
        Thread.sleep(300);
        edt(() -> uiClick("拒绝"));
        Thread.sleep(300);
        expect(Boolean.FALSE.equals(denied[0].getNow(null)), "拒绝按钮写入 future");
        edt(() -> denyHolder[0].finish("用户拒绝了本次工具调用，工具未执行。", false, "{\"path\": \"b.txt\"}"));
        // 已结束的卡片再请求审批：必须立刻决成 false，不能让等待方吊死
        java.util.concurrent.CompletableFuture<Boolean>[] late = new java.util.concurrent.CompletableFuture[1];
        edt(() -> {
            late[0] = new java.util.concurrent.CompletableFuture<>();
            approvalHolder[0].requestApproval(late[0]);
        });
        expect(Boolean.FALSE.equals(late[0].getNow(null)), "终态卡请求审批立即拒绝");
        Thread.sleep(300);
        int toolBase = store.getActive().getMessages().size() - 4; // 前两张工具卡 + 本段两张
        expect("ok".equals(store.getActive().getMessages().get(toolBase + 2).toolStatus())
                && "error".equals(store.getActive().getMessages().get(toolBase + 3).toolStatus()),
                "允许/拒绝终态落库");
        System.out.println("APPROVAL_OK");

        // —— 窄窗口回归（计划外）：参数摘要很长时头部 EAST 钉住的"允许/拒绝"不能被挤出行外裁掉
        // （原一行 FlowLayout 把参数摘要排到按钮前面，窗口一窄按钮就出卡外，点不到只能拉宽）；
        // 点头部展开还能看到参数全文，而不是 80 字截断的摘要 ——
        int[] narrowOldSize = new int[2];
        edt(() -> {
            narrowOldSize[0] = WINDOW.getWidth();
            narrowOldSize[1] = WINDOW.getHeight();
        });
        String longArgs = "{\"path\": \"workspace/very/long/directory/name/tool-args/overflow/summary/cut.json\", "
                + "\"mode\": \"overwrite\"}";
        Window.ToolCallHandle[] narrowHolder = new Window.ToolCallHandle[1];
        java.util.concurrent.CompletableFuture<Boolean>[] narrowDecision = new java.util.concurrent.CompletableFuture[1];
        edt(() -> {
            narrowHolder[0] = WINDOW.addToolCall("write_file", longArgs, "call_narrow");
            narrowDecision[0] = new java.util.concurrent.CompletableFuture<>();
            narrowHolder[0].requestApproval(narrowDecision[0]);
        });
        Thread.sleep(300);
        edt(() -> WINDOW.setSize(760, narrowOldSize[1])); // 缩到 760 宽：老布局下按钮已被裁出卡外
        Thread.sleep(600); // 等 resize 触发的 fitAllCardHeights 重排
        expect(buttonFullyVisible("允许"), "窄窗口下允许按钮完整可见（未被裁出行外）");
        // 展开参数全文：点卡片树里最后一个 tool-header（DFS 顺序末位 = 最新的待确认卡）
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            JPanel target = null;
            for (Component component : all) {
                if (component instanceof JPanel jPanel && "tool-header".equals(jPanel.getName())) {
                    target = jPanel;
                }
            }
            if (target == null) {
                throw new AssertionError("没找到工具卡头部");
            }
            target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 5, 5, 1, false));
        });
        Thread.sleep(400);
        expect(uiHasTextAreaStartingWith("{\"path\": \"workspace/very/long"), "展开显示参数全文");
        edt(() -> uiClick("允许")); // 点击落到按钮上 future 才会被写——顺带证明窄窗口下可点
        Thread.sleep(300);
        expect(Boolean.TRUE.equals(narrowDecision[0].getNow(null)), "窄窗口下允许按钮仍可点击");
        edt(() -> narrowHolder[0].finish("写入完成", true, longArgs)); // 收尾：不留待确认标注
        edt(() -> WINDOW.setSize(narrowOldSize[0], narrowOldSize[1])); // 还原尺寸（防抖落盘存回原值）
        Thread.sleep(600);
        System.out.println("NARROW_OK");

        // 工具卡演示把主区切到了新会话，切回 Markdown 样例会话供后面 8 号悬停菜单截图用
        edt(() -> WINDOW.getStore().switchTo(c3.getId()));
        Thread.sleep(800);

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

        // —— 截图 12：会话右键菜单（清掉搜索后，对列表合成一个 popupTrigger 按下事件弹菜单） ——
        edt(() -> WINDOW.getSidebar().setSearchQuery(""));
        Thread.sleep(300);
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof JList) {
                    component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_PRESSED,
                            System.currentTimeMillis(), MouseEvent.BUTTON3_MASK, 30, 20, 1, true));
                    break;
                }
            }
        });
        Thread.sleep(500);
        capture(args[0] + "-12-context-menu.png");

        // —— 收尾：合成 MOUSE_EXITED 藏掉 8 号起的悬停"…"，clearSelectedPath 关掉右键菜单 ——
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof JPanel) {
                    component.dispatchEvent(new MouseEvent(component,
                            MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, 5, 5, 0, false));
                }
            }
            MenuSelectionManager.defaultManager().clearSelectedPath();
        });
        Thread.sleep(400);

        // —— 长会话窗口化（P2-2）：300 条消息，卡片全量占位、只物化视口附近的组件 ——
        // 窗口挂着侧栏/主区监听，所有 store 操作（含 newConversation）都必须进 EDT（踩坑 #1）；
        // 分批灌入让窗口保持响应，避免整块 EDT 长时间冻结
        Conversation[] c4Holder = new Conversation[1];
        edt(() -> c4Holder[0] = store.newConversation());
        Conversation c4 = c4Holder[0];
        for (int from = 0; from < 300; from += 50) {
            final int base = from;
            edt(() -> {
                for (int i = base; i < base + 50; i++) {
                    String text = i % 2 == 0
                            ? "第 " + i + " 问：这段文字用来撑出一条足够折行的用户消息，长度比一行长不少，"
                                    + "验证窗口化下卡片高度测量依然正确。"
                            : "第 " + i + " 答：**加粗**与`行内代码`混排。\n\n- 列表项一\n- 列表项二\n\n> 引用一下\n\n第二段正文。";
                    store.appendMessage(c4.getId(), i % 2 == 0 ? Conversation.ROLE_USER : Conversation.ROLE_AI, text);
                }
            });
            Thread.sleep(30);
        }
        edt(() -> WINDOW.getStore().switchTo(c3.getId())); // 先切走，再切回 c4 走完整的重建路径
        Thread.sleep(1000);
        edt(() -> WINDOW.getStore().switchTo(c4.getId()));
        Thread.sleep(2500);
        int[] counters = new int[2];
        edt(() -> {
            counters[0] = WINDOW.getWaterfallPanel().getComponentCount();
            counters[1] = countTextPanes(WINDOW);
        });
        expect(counters[0] == 300, "长会话卡片全量占位: " + counters[0]);
        expect(counters[1] > 10 && counters[1] < 120, "窗口化后仅视口附近物化: " + counters[1]);
        // 滚回顶部：首卡应被物化，组件总数仍受限
        // 滚回顶部（走滚动条 setValue，与真实用户拖拽同路径）：首卡应被物化，组件总数仍受限
        edt(() -> {
            java.awt.Container viewportParent = WINDOW.getWaterfallPanel().getParent().getParent();
            if (viewportParent instanceof javax.swing.JScrollPane scrollPane) {
                scrollPane.getVerticalScrollBar().setValue(0);
            }
        });
        Thread.sleep(600);
        edt(() -> {
            counters[0] = ((Container) WINDOW.getWaterfallPanel().getComponent(0)).getComponentCount();
            counters[1] = countTextPanes(WINDOW);
        });
        expect(counters[0] > 0, "滚回顶部后首卡已物化");
        expect(counters[1] < 120, "滚动后物化窗口仍受限: " + counters[1]);
        capture(args[0] + "-13-long-conversation.png");
        System.out.println("WINDOWING_OK");

        // —— 截图 14：深色主题（retheme 原地重建整窗，Markdown 会话校验卡片/思考块/代码块/引用配色） ——
        // 12 号的合成点击会把列表第一项选中切走会话，先切回 Markdown 样例会话
        edt(() -> WINDOW.getStore().switchTo(c3.getId()));
        Thread.sleep(1200);
        edt(() -> {
            AppSettings.get().setUiTheme("dark");
            WINDOW.retheme();
        });
        Thread.sleep(1500);
        scrollWaterfallToTop(); // retheme 重建整窗会滚到底部，滚回顶部让思考块入镜
        capture(args[0] + "-14-dark.png");

        // —— 截图 15：深色下的设置面板（主题下拉应显示"深色"，输入控件跟随弹层配色） ——
        edt(() -> {
            SettingsDialog dialog = new SettingsDialog(WINDOW, false);
            dialog.setVisible(true);
            dialogHolder[0] = dialog;
        });
        // 勾上"最小化到托盘"，顺带验证选中态的对勾图标在深色下的渲染
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(dialogHolder[0], all);
            for (Component component : all) {
                if (component instanceof JCheckBox box) {
                    box.setSelected(true);
                }
            }
        });
        Thread.sleep(600);
        edt(() -> {
            SettingsDialog dialog = dialogHolder[0];
            BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
            dialog.printAll(image.createGraphics());
            try {
                ImageIO.write(image, "png", new File(args[0] + "-15-dark-settings.png"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            dialog.dispose();
        });

        // —— 托盘最小化（P2-3）：关按钮=隐藏到托盘而非退出；放最后，这一步会把窗口藏起来 ——
        if (!GraphicsEnvironment.isHeadless() && SystemTray.isSupported()) {
            SystemTray tray = SystemTray.getSystemTray();
            boolean oldTrayPref = AppSettings.get().isMinimizeToTray();
            edt(() -> {
                // 前面建窗时设过 DISPOSE_ON_CLOSE，这里换回窗口自管的 DO_NOTHING 再验关闭路径
                WINDOW.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                // 关闭行为现在实时读 AppSettings 的偏好（托盘图标常驻注册），先把偏好打开
                AppSettings.get().setMinimizeToTray(true);
                WINDOW.setMinimizeToTray(true);
            });
            expect(tray.getTrayIcons().length == 1, "托盘图标已注册");
            edt(() -> WINDOW.dispatchEvent(
                    new WindowEvent(WINDOW, WindowEvent.WINDOW_CLOSING)));
            Thread.sleep(400);
            expect(!WINDOW.isVisible(), "关闭按钮最小化到托盘（窗口隐藏）");
            expect(WINDOW.isDisplayable(), "窗口未销毁（托盘可恢复）");
            edt(() -> {
                AppSettings.get().setMinimizeToTray(oldTrayPref); // 恢复真实偏好
                WINDOW.setVisible(true);
            });
            expect(tray.getTrayIcons().length == 1, "托盘图标常驻（不随偏好注销）");
        }
        System.out.println("TRAY_OK");

        // —— 截图 20：新建对话的工作区弹窗（计划外）。模态弹窗：开窗的点击用 invokeLater 派发
        // （不等待——edt 会卡在 doClick 里等弹窗关闭），EDT 进模态泵后仍派发后续任务；
        // 关窗的点击走 edt，弹窗关闭后 doClick 才返回 ——
        int conversationsBefore = WINDOW.getStore().getConversations().size();
        Files.createDirectories(AppSettings.defaultWorkspace()); // 默认目录先在着，确定时不触发"是否创建"确认
        SwingUtilities.invokeLater(() -> uiClickAnyWindow("＋ 新对话"));
        Thread.sleep(800);
        expect(uiHasTextAnyWindow("新建对话的工作区——工具的相对路径以此为基准"), "工作区弹窗打开");
        expect(uiHasTextAnyWindow("浏览…"), "弹窗里有浏览按钮（资源管理器选目录）");
        captureDialog(args[0] + "-20-workspace.png", "选择工作区");
        edt(() -> uiClickAnyWindow("取消"));
        Thread.sleep(300);
        expect(WINDOW.getStore().getConversations().size() == conversationsBefore, "取消不新建会话");
        // 确定默认路径：新建会话 workspace 保持 null（默认不落到会话上，recents 也不记默认）
        SwingUtilities.invokeLater(() -> uiClickAnyWindow("＋ 新对话"));
        Thread.sleep(800);
        edt(() -> uiClickAnyWindow("开始对话"));
        Thread.sleep(600);
        expect(WINDOW.getStore().getConversations().size() == conversationsBefore + 1, "确定后新建会话");
        expect(WINDOW.getStore().getActive().getWorkspace() == null, "默认工作区不落到会话");
        expect(sidebarGroupRows().stream().anyMatch(g -> "默认工作区".equals(g[0]) && g[1] == null),
                "新建默认会话后默认分组仍在");

        // —— 侧栏分组跟会话 + 组头加号（原"工作区显示条跟会话"+"复制路径"随条移除） ——
        Files.createDirectories(Path.of(wsPath)); // 目录先在着，确定时不触发"是否创建"确认弹窗
        SwingUtilities.invokeLater(() -> uiClickAnyWindow("＋ 新对话"));
        Thread.sleep(800);
        edt(() -> uiTypeWorkspacePath(wsPath));
        edt(() -> uiClickAnyWindow("开始对话"));
        Thread.sleep(600);
        expect(wsPath.equals(WINDOW.getStore().getActive().getWorkspace()), "弹窗选定的工作区落到会话");
        String wsName = Path.of(wsPath).getFileName().toString();
        expect(sidebarGroupRows().stream().anyMatch(g -> wsName.equals(g[0]) && wsPath.equals(g[1])),
                "绑定的工作区在侧栏有自己的分组");

        // 组头加号：跳过弹窗直接新建；空会话复用语义生效（当前空对话改绑，不堆空会话）
        int sizeBeforePlus = WINDOW.getStore().getConversations().size();
        edt(() -> WINDOW.getSidebar().newConversationInGroup(null)); // 默认组加号：null 绑定
        Thread.sleep(400);
        expect(WINDOW.getStore().getConversations().size() == sizeBeforePlus
                && WINDOW.getStore().getActive().getWorkspace() == null, "默认组加号：空会话改绑默认不新建");
        edt(() -> WINDOW.getSidebar().newConversationInGroup(wsPath)); // 绑定组加号
        Thread.sleep(400);
        expect(WINDOW.getStore().getConversations().size() == sizeBeforePlus
                && wsPath.equals(WINDOW.getStore().getActive().getWorkspace()), "绑定组加号：空会话改绑组工作区");
        expect(sidebarGroupRows().stream().anyMatch(g -> wsName.equals(g[0]) && wsPath.equals(g[1])),
                "加号新建后绑定分组仍在");
        capture(args[0] + "-20b-workspace-groups.png");
        System.out.println("WORKSPACE_OK");

        // —— 输入框高度自适应（计划外）：多行先增高，到上限（240）后不再长、由内部滚动接管
        //  （长内容曾只能看见首两行，新打的字"藏在视口外"），清空回落到初始高度 ——
        edt(() -> WINDOW.getInputTextArea().setText("第一行\n第二行\n第三行"));
        Thread.sleep(300);
        int grownHeight = WINDOW.getInputPreferredHeight();
        expect(grownHeight > 48, "多行输入增高（超过原固定高 48）");
        StringBuilder manyLines = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            manyLines.append("第").append(i + 1).append("行\n");
        }
        edt(() -> WINDOW.getInputTextArea().setText(manyLines.toString()));
        Thread.sleep(400);
        expect(WINDOW.getInputPreferredHeight() == 240, "触帽后不再增高（内部滚动接管）");
        capture(args[0] + "-21-input-grow.png");
        edt(() -> WINDOW.getInputTextArea().setText(""));
        Thread.sleep(400);
        expect(WINDOW.getInputPreferredHeight() == 48, "清空后回落到初始高度（不只长不缩）");
        System.out.println("INPUT_OK");

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

    /** 把消息区滚回顶部（走滚动条 setValue，与真实用户拖拽同路径） */
    private static void scrollWaterfallToTop() throws Exception {
        edt(() -> {
            java.awt.Container viewportParent = WINDOW.getWaterfallPanel().getParent().getParent();
            if (viewportParent instanceof javax.swing.JScrollPane scrollPane) {
                scrollPane.getVerticalScrollBar().setValue(0);
            }
        });
        Thread.sleep(400);
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("校验失败: " + what);
        }
    }

    /** 在仓库里按 id 找会话的置顶标记（分叉后列表首位不一定是目标会话） */
    private static boolean pinnedOf(ConversationStore store, String id) {
        for (Conversation conversation : store.getConversations()) {
            if (conversation.getId().equals(id)) {
                return conversation.isPinned();
            }
        }
        return false;
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

    /** 窗口里是否存在显示该文本的标签/按钮（审批卡断言用；自己跳 EDT，Swing 树不许在后台读） */
    private static boolean uiHasText(String text) throws Exception {
        boolean[] found = new boolean[1];
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof javax.swing.AbstractButton button && text.equals(button.getText())) {
                    found[0] = true;
                    return;
                }
                if (component instanceof javax.swing.JLabel label && text.equals(label.getText())) {
                    found[0] = true;
                    return;
                }
            }
        });
        return found[0];
    }

    /** 点击显示该文本的按钮（必须在 EDT 里调用，与真实点击同一入口） */
    private static void uiClick(String text) {
        List<Component> all = new ArrayList<>();
        collectComponents(WINDOW, all);
        for (Component component : all) {
            if (component instanceof javax.swing.JButton button
                    && text.equals(button.getText()) && button.isShowing() && button.isEnabled()) {
                button.doClick();
                return;
            }
        }
        throw new AssertionError("没找到可点击的按钮: " + text);
    }

    /** 全部顶层窗口里点该文本的按钮：工作区弹窗是独立 JDialog，不在 WINDOW 的组件树里 */
    private static void uiClickAnyWindow(String text) {
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            List<Component> all = new ArrayList<>();
            collectComponents(window, all);
            for (Component component : all) {
                if (component instanceof javax.swing.JButton button
                        && text.equals(button.getText()) && button.isShowing() && button.isEnabled()) {
                    button.doClick();
                    return;
                }
            }
        }
        throw new AssertionError("没找到可点击的按钮: " + text);
    }

    /** 把弹窗（非主窗）里显示中的输入框设为该值（工作区弹窗路径框断言用；只跳主窗防误命中） */
    private static void uiTypeWorkspacePath(String text) {
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            if (window == WINDOW) {
                continue;
            }
            List<Component> all = new ArrayList<>();
            collectComponents(window, all);
            for (Component component : all) {
                if (component instanceof javax.swing.JTextField field && field.isShowing()) {
                    field.setText(text);
                    return;
                }
            }
        }
        throw new AssertionError("没找到工作区路径输入框");
    }

    /** 全部顶层窗口里是否有显示该全等文本的按钮/标签（工作区弹窗断言用，同 uiHasText 但跨窗口） */
    private static boolean uiHasTextAnyWindow(String text) throws Exception {
        boolean[] found = new boolean[1];
        edt(() -> {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                List<Component> all = new ArrayList<>();
                collectComponents(window, all);
                for (Component component : all) {
                    if (component instanceof javax.swing.JLabel label && text.equals(label.getText())) {
                        found[0] = true;
                        return;
                    }
                    if (component instanceof javax.swing.AbstractButton button && text.equals(button.getText())) {
                        found[0] = true;
                        return;
                    }
                }
            }
        });
        return found[0];
    }

    /** 按标题找到正在显示的 JDialog 截图（工作区弹窗，WINDOW.printAll 拍不到独立窗口） */
    private static void captureDialog(String path, String dialogTitle) throws Exception {
        edt(() -> {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                if (window instanceof javax.swing.JDialog dialog
                        && dialogTitle.equals(dialog.getTitle()) && dialog.isShowing()) {
                    BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(),
                            BufferedImage.TYPE_INT_RGB);
                    dialog.printAll(image.createGraphics());
                    try {
                        ImageIO.write(image, "png", new File(path));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return;
                }
            }
            throw new AssertionError("没找到弹窗: " + dialogTitle);
        });
    }

    /** 窗口里是否有正在显示（isShowing）的全等文本按钮/标签：搜索条显隐断言用——
     *  收起后的组件还在视图树里，只是不可见，uiHasText 会误报 */
    private static boolean uiHasShowingText(String text) throws Exception {
        boolean[] found = new boolean[1];
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (!component.isShowing()) {
                    continue;
                }
                if (component instanceof javax.swing.AbstractButton button && text.equals(button.getText())
                        || component instanceof javax.swing.JLabel label && text.equals(label.getText())) {
                    found[0] = true;
                    return;
                }
            }
        });
        return found[0];
    }

    /** 窗口里是否存在文本包含该片段的标签（思考块"已思考"标注断言用；uiHasText 是全等匹配） */
    private static boolean uiHasLabelContaining(String fragment) throws Exception {        boolean[] found = new boolean[1];
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof javax.swing.JLabel label
                        && label.getText() != null && label.getText().contains(fragment)) {
                    found[0] = true;
                    return;
                }
            }
        });
        return found[0];
    }

    /** 窗口里是否存在内容包含该片段的 JTextPane（思考块内容展开/收起断言用） */
    private static boolean uiHasTextPaneContaining(String fragment) throws Exception {
        boolean[] found = new boolean[1];
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof javax.swing.JTextPane pane
                        && pane.getText() != null && pane.getText().contains(fragment)) {
                    found[0] = true;
                    return;
                }
            }
        });
        return found[0];
    }

    /** 窗口里是否存在内容以该前缀开头的 JTextArea（工具卡结果区展开/收起断言用） */
    private static boolean uiHasTextAreaStartingWith(String prefix) throws Exception {
        boolean[] found = new boolean[1];
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof javax.swing.JTextArea area
                        && area.getText() != null && area.getText().startsWith(prefix)) {
                    found[0] = true;
                    return;
                }
            }
        });
        return found[0];
    }

    /** 按组件名点击（工具卡头部 tool-header / 独立思考区头部 thinking-header 的折叠切换）：
     *  合成 mouseClicked 派发给第一个可见的同名组件，与真实点击同一事件入口 */
    private static void uiClickNamed(String name) {
        List<Component> all = new ArrayList<>();
        collectComponents(WINDOW, all);
        for (Component component : all) {
            if (name.equals(component.getName()) && component.isShowing()) {
                component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(), 0, 5, 5, 1, false));
                return;
            }
        }
        throw new AssertionError("没找到可点击组件: " + name);
    }

    /** 全部顶层窗口里是否有"显示中"的全等文本标签/按钮（设置分页断言用：CardLayout 隐藏页
     *  的组件仍在树里，uiHasTextAnyWindow 不看显隐会把非当前页也报成"在"） */
    private static boolean uiHasShowingTextAnyWindow(String text) throws Exception {
        boolean[] found = new boolean[1];
        edt(() -> {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                List<Component> all = new ArrayList<>();
                collectComponents(window, all);
                for (Component component : all) {
                    if (component.isShowing()) {
                        if (component instanceof javax.swing.AbstractButton button
                                && text.equals(button.getText())) {
                            found[0] = true;
                            return;
                        }
                        if (component instanceof javax.swing.JLabel label && text.equals(label.getText())) {
                            found[0] = true;
                            return;
                        }
                    }
                }
            }
        });
        return found[0];
    }

    /** 显示中的按钮（全等文本）是否完整落在主窗范围内（窄窗口审批按钮被裁的回归断言用） */
    private static boolean buttonFullyVisible(String text) throws Exception {
        boolean[] ok = new boolean[1];
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof javax.swing.JButton button
                        && text.equals(button.getText()) && button.isShowing()) {
                    java.awt.Rectangle buttonBounds = new java.awt.Rectangle(
                            button.getLocationOnScreen(), button.getSize());
                    ok[0] = WINDOW.getBounds().contains(buttonBounds);
                    return;
                }
            }
        });
        return ok[0];
    }

    /** 读系统剪贴板文本（用户消息复制断言用）；不可读或非文本返回 null */
    private static String clipboardText() {
        try {
            Object value = Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getContents(null).getTransferData(DataFlavor.stringFlavor);
            return value instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 划词复制断言：在包含该文本的显示 pane 里选中 [start, end) 并派发 Ctrl+C。
     *  事件直接派发给该组件——TextAction 对事件源组件生效，不依赖窗口焦点。
     *  setSelectionVisible(true) 强制高亮可见（正常交互是持焦才高亮；自动化里窗口可能
     *  不在前台、requestFocusInWindow 是尽力而为，截图要确定性） */
    private static void selectAndCtrlC(String contained, int start, int end) throws Exception {
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof javax.swing.JTextPane pane
                        && pane.getText() != null && pane.getText().contains(contained)) {
                    // 选区绘制查 hasFocus：自动化时窗口多半不在前台，先把窗口带到前台再要焦点
                    WINDOW.toFront();
                    WINDOW.requestFocus();
                    pane.requestFocusInWindow();
                    pane.setCaretPosition(start);
                    pane.moveCaretPosition(end);
                    pane.getCaret().setSelectionVisible(true);
                    pane.dispatchEvent(new java.awt.event.KeyEvent(pane, java.awt.event.KeyEvent.KEY_PRESSED,
                            System.currentTimeMillis(), java.awt.event.KeyEvent.CTRL_DOWN_MASK,
                            java.awt.event.KeyEvent.VK_C, java.awt.event.KeyEvent.CHAR_UNDEFINED));
                    return;
                }
            }
            throw new AssertionError("没找到可划词的文本组件: " + contained);
        });
    }

    /** 链接命中断言（P1-6）：在显示中的正文 pane 里扫属性等于该 URL 的字符偏移，
     *  用 modelToView 反推坐标再走 linkUrlAt 验证能取回——不真开浏览器。
     *  链接首字符的 rect 可能是零宽（前面贴着空格的视图特性），所以扫全部偏移不设宽度假定 */
    private static boolean uiLinkHit(String url) throws Exception {
        boolean[] found = new boolean[1];
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof javax.swing.JTextPane pane
                        && pane.getText() != null && pane.getText().contains(url)) {
                    javax.swing.text.StyledDocument doc = pane.getStyledDocument();
                    for (int offset = 0; offset < doc.getLength() && !found[0]; offset++) {
                        Object attr = doc.getCharacterElement(offset).getAttributes()
                                .getAttribute(MarkdownRenderer.LINK_URL_ATTRIBUTE);
                        if (!url.equals(attr)) {
                            continue;
                        }
                        try {
                            java.awt.Rectangle rect = pane.modelToView2D(offset).getBounds();
                            if (rect != null && url.equals(MarkdownRenderer.linkUrlAt(pane,
                                    new java.awt.Point(rect.x + rect.width / 2, rect.y + rect.height / 2)))) {
                                found[0] = true;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    return;
                }
            }
        });
        return found[0];
    }

    /** 侧栏某会话条目的渲染 HTML（含状态标注）：直接调渲染器拿文本，不依赖列表可见
     *  （4 号截图把侧栏折叠了，可见单元格不保证存在）。找不到该会话返回空串 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String sidebarBadgeHtml(String conversationId) throws Exception {
        String[] html = new String[1];
        edt(() -> {
            JList rawList = findConvList(WINDOW);
            if (rawList == null) {
                return;
            }
            for (int i = 0; i < rawList.getModel().getSize(); i++) {
                Object value = rawList.getModel().getElementAt(i);
                if (value instanceof Conversation conversation && conversation.getId().equals(conversationId)) {
                    Component cell = rawList.getCellRenderer()
                            .getListCellRendererComponent(rawList, value, i, false, false);
                    html[0] = cell instanceof javax.swing.JLabel label ? label.getText() : "";
                    return;
                }
            }
        });
        return html[0] == null ? "" : html[0];
    }

    /** 侧栏当前全部工作区分组头按行序快照 = [显示名, 绑定值]（绑定值 null=默认组），复制侧栏行模型 */
    private static java.util.List<String[]> sidebarGroupRows() throws Exception {
        List<String[]>[] rows = new List[1];
        rows[0] = new ArrayList<>();
        edt(() -> {
            JList<?> rawList = findConvList(WINDOW);
            if (rawList != null) {
                java.util.List<String[]> collected = new ArrayList<>();
                for (int i = 0; i < rawList.getModel().getSize(); i++) {
                    if (rawList.getModel().getElementAt(i) instanceof Sidebar.GroupRow row) {
                        collected.add(new String[]{row.title, row.workspace});
                    }
                }
                rows[0] = collected;
            }
        });
        return rows[0];
    }

    /** 深度优先找第一个 JList（侧栏会话列表） */
    private static JList<?> findConvList(Component component) {
        if (component instanceof JList<?> list) {
            return list;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JList<?> found = findConvList(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 全窗口里的 JTextPane 数量：物化窗口断言用（未窗口化的 300 条会话会有近千个） */
    private static int countTextPanes(Component component) {
        int count = component instanceof JTextPane ? 1 : 0;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                count += countTextPanes(child);
            }
        }
        return count;
    }
}

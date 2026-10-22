package io.github.preschoolller.agentjava.frontend.gui;

import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import io.github.preschoolller.agentjava.frontend.store.Conversation;
import io.github.preschoolller.agentjava.frontend.store.ConversationStore;
import io.github.preschoolller.agentjava.frontend.store.Message;
import io.github.preschoolller.agentjava.frontend.store.ModelMeta;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.imageio.ImageIO;
import javax.swing.JCheckBox;
import javax.swing.JList;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * 前端 GUI 回归（原 foragent/tools/Shot.java 的 UI 段整体平移为 JUnit，按 Shot 原顺序编为有序用例）。
 * 跑法：mvn test（surefire 单 fork JVM 内按类跑）；截图产物落在 -Dregression.dir（默认 target/regression，
 * gitignore 内）。产出：窗口默认态/侧栏分组、拖宽、切会话、生成中、折叠、设置面板分页（通用页审核通知
 * 三开关/工具页）、Markdown 渲染与划词搜索、流式落库、工具卡折叠、权限确认卡、窄窗口不裁按钮、
 * 悬停菜单、置顶/搜索高亮/片段/右键菜单、长会话窗口化、深色主题、托盘最小化、工作区弹窗与
 * 侧栏分组/组头加号/组头折叠、输入框高度自适应，共 28 张截图。
 * 注意：Swing 视图树非线程安全，所有 UI 操作必须包在 EDT 里执行（见踩坑记录 #1）。
 * <p>
 * 交互巡检向导（用户点名）：-Dgui.interactive=true 时每个观察点在截图后弹说明卡，人工点
 * "下一个"才继续——补自动断言够不着的观感/手感维度（配色对齐、字形 fallback、真实鼠标手感）。
 * 说明卡默认<b>非模态</b>（MODELESS + alwaysOnTop 钉主窗右下角）：等人期间可以随便点被测
 * 窗口，"请顺手试"的站点就靠这条腿；有真模态弹窗在场时退回 Ui.confirmDialog 模态卡
 * （Swing 模态规则：显示中的 APPLICATION_MODAL 弹窗把事后创建的窗也锁进模态作用域，
 * 非模态卡会被拦死点不了）。点"取消/终止"=人工终止向导，剩余观察点静默自动跑完。
 * -Dgui.interactive.auto=true 再叠一层自动过站：卡照弹、600ms 自动点"下一个"并落一行
 * 过站日志（agent/CI 冒烟验证交互管线用，人工巡检不用）。两开关默认关，绝不自动卡人。
 */
@TestMethodOrder(OrderAnnotation.class)
class WindowGuiTest {

    /** 截图输出目录（产物落在 gitignore 的 target/ 下），-Dregression.dir 可覆盖 */
    private static final Path SCREENS = Path.of(System.getProperty("regression.dir", "target/regression"));

    /** 交互巡检向导：真交互 / 自动过站（见类注释） */
    private static boolean WIZARD;
    private static boolean WIZARD_AUTO;
    private static int gateNo;
    private static boolean gateAborted;

    /** 窗口挂在侧栏/主区监听后，store 操作必须进 EDT；窗口建起来之前随便（见用例 s01 前 @BeforeAll） */
    private static ConversationStore store;
    private static Conversation c1;
    private static Conversation c2;
    private static Conversation c3;
    private static Window WINDOW;
    private static SettingsDialog dialogHolder;
    private static List<ModelMeta> oldMetas;
    private static String oldModel;
    private static String wsPath;
    private static Path storePath;

    /** 审批/窄窗口段的 handle 跨 EDT 存放位（EDT 里创建，后续 edt 段收尾用） */
    private static final Window.ToolCallHandle[] APPROVAL_HOLDER = new Window.ToolCallHandle[1];
    private static final Window.ToolCallHandle[] DENY_HOLDER = new Window.ToolCallHandle[1];
    private static final Window.ToolCallHandle[] NARROW_HOLDER = new Window.ToolCallHandle[1];

    @BeforeAll
    static void setUpAll() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "无显示环境（headless）：GUI 回归跳过，纯模型单测不受影响");
        Files.createDirectories(SCREENS);
        WIZARD = Boolean.getBoolean("gui.interactive");
        WIZARD_AUTO = WIZARD && Boolean.getBoolean("gui.interactive.auto");
        // 截图配色固定浅色：开发者本机存过深色时回归图才有确定基线（只改内存，不落盘）
        AppSettings.get().setUiTheme("light");
        Path dir = Files.createTempDirectory("agentjava-gui");
        store = new ConversationStore(dir.resolve("shot.json"));
        // —— 造两段会话（c1 六条消息让瀑布流溢出，滚动条才可见） ——
        c1 = store.newConversation();
        store.appendMessage(c1.getId(), Conversation.ROLE_USER, "帮我看看这段代码有什么问题");
        store.appendMessage(c1.getId(), Conversation.ROLE_AI,
                "好的，请把代码贴出来。\n收到后我会逐段分析并给出修改建议，重点关注空指针风险、资源关闭顺序以及并发场景下的可见性问题，这几类问题在实际项目里最常见。");
        store.appendMessage(c1.getId(), Conversation.ROLE_USER, "为什么并发场景下可见性会出问题？");
        store.appendMessage(c1.getId(), Conversation.ROLE_AI,
                "因为每个线程可能把共享变量缓存在自己的工作内存里，写回主内存的时机不确定，其他线程读到的可能是旧值。"
                        + "解决思路是使用 volatile、锁或者原子类来建立 happens-before 关系，保证一个线程的写对另一个线程立即可见。");
        store.appendMessage(c1.getId(), Conversation.ROLE_USER, "volatile 能保证原子性吗？");
        store.appendMessage(c1.getId(), Conversation.ROLE_AI,
                "不能。volatile 只保证可见性和禁止指令重排，像 i++ 这种复合操作还是需要原子类或锁。");
        c2 = store.newConversation();
        store.appendMessage(c2.getId(), Conversation.ROLE_USER, "总结一下今天聊的内容");
        store.appendMessage(c2.getId(), Conversation.ROLE_AI, "今天聊了前端布局的三点改动。");
        // c3：Markdown 渲染样例（6 号截图）
        c3 = store.newConversation();
        store.appendMessage(c3.getId(), Conversation.ROLE_USER, "给我演示一下 Markdown 渲染");
        store.appendMessage(c3.getId(), Conversation.ROLE_AI, SampleMd.SAMPLE_MD);
        // 工作区弹窗段用（目录到 s17 才真正创建，那时确定时不会触发"是否创建"确认）
        wsPath = dir.resolve("ws-project").toString();
        storePath = dir.resolve("shot.json");
        edt(() -> {
            WINDOW = new Window("Agent", store);
            WINDOW.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            // 尺寸/位置由 Window 按 AppSettings 恢复（不强制 1280×720，避免把回归用的尺寸写回用户设置）
            WINDOW.setVisible(true);
        });
        Thread.sleep(1500);
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (WINDOW != null) {
            edt(WINDOW::dispose);
        }
        // 托盘图标不随窗口销毁自动注销，收干净防僵尸图标残留
        if (!GraphicsEnvironment.isHeadless() && SystemTray.isSupported()) {
            for (java.awt.TrayIcon icon : SystemTray.getSystemTray().getTrayIcons()) {
                SystemTray.getSystemTray().remove(icon);
            }
        }
    }

    @Test
    @Order(1)
    void s01_windowOpensAndDefaults() throws Exception {
        // 输入区右下角控件：模型/思考强度默认选中项非空
        assertInputDefaults();
        // 侧栏按工作区分组：回归用会话都没绑工作区 → 单一"默认工作区"组头；主区工作区显示条已移除
        assertTrue(sidebarGroupRows().stream().anyMatch(g -> "默认工作区".equals(g[0]) && g[1] == null),
                "侧栏默认工作区分组在");
        assertFalse(uiHasText("复制路径"), "主区工作区显示条已移除（复制路径钮随条消失）");
        captureGated("window-1-expanded.png",
                "窗口默认态：侧栏单一「默认工作区」分组头；主区瀑布流高度自适应；输入区右下角模型/思考强度/权限模式的默认选中。");
    }

    @Test
    @Order(2)
    void s02_wideSidebar() throws Exception {
        // —— 截图 1b：分隔条拖到 360px（模拟手动拖宽侧栏） ——
        edt(() -> WINDOW.getSplitPane().setDividerLocation(360));
        Thread.sleep(600);
        captureGated("window-1b-wide.png",
                "分隔条拖到 360 宽后的侧栏形态，条目横向更舒展（点完下一个会拖回默认宽）。请顺手试：真实拖一下分隔条比对手感与合成路径是否一致。");
        edt(() -> WINDOW.getSplitPane().setDividerLocation(Sidebar.EXPANDED_WIDTH));
        Thread.sleep(400);
    }

    @Test
    @Order(3)
    void s03_switchConversation() throws Exception {
        // —— 截图 2：切换到第一段对话（用户气泡靠右 + 长文本高度自适应 + 滚到底部） ——
        edt(() -> WINDOW.getStore().switchTo(c1.getId()));
        Thread.sleep(1200);
        captureGated("window-2-switched.png",
                "切到第一段对话：用户消息气泡靠右、长文本卡片高度自适应、视图自动滚到底部。");
    }

    @Test
    @Order(4)
    void s04_generatingBadge() throws Exception {
        // —— 截图 3：生成中状态（停止按钮 + 胶囊文案） ——
        edt(() -> WINDOW.setGenerating(true));
        Thread.sleep(500);
        captureGated("window-3-generating.png", "生成中状态：输入区出现停止按钮；侧栏该会话条目亮「生成中」胶囊（下一个会熄灭它）。");
        assertTrue(sidebarBadgeHtml(store.getActive().getId()).contains(">生成中<"), "侧栏亮\"生成中\"标注");
        edt(() -> WINDOW.setGenerating(false));
        assertFalse(sidebarBadgeHtml(store.getActive().getId()).contains(">生成中<"), "生成结束侧栏标注熄灭");
    }

    @Test
    @Order(5)
    void s05_collapsedSidebar() throws Exception {
        // —— 截图 4：折叠侧栏 ——
        edt(() -> {
            WINDOW.getSidebar().setExpanded(false);
            WINDOW.getSplitPane().setDividerLocation(Sidebar.COLLAPSED_WIDTH);
            WINDOW.getContentPane().revalidate();
        });
        Thread.sleep(800);
        captureGated("window-4-collapsed.png", "侧栏折叠后的窄形态，只剩汉堡按钮和主区。");
    }

    @Test
    @Order(6)
    void s06_settingsDialogPagination() throws Exception {
        // —— 截图 5：设置面板 ——
        // 模型目录先钉成两条（内存改动不落盘，同主题偏好同款）：行删除钮"打开即可用"
        //（目录>1 的规则接替原模型列表删除钮）与展开参数区断言依赖确定形状——
        // 曾经 modelRow 写死 setEnabled(false) 把初始可用态按灭（踩坑 #25），这里钉死
        oldMetas = AppSettings.get().getActiveProfile().getModelMetas();
        oldModel = AppSettings.get().getActiveProfile().getModel();
        ModelMeta shotA = new ModelMeta("model-a");
        shotA.setCtxWindow("256K");
        shotA.setMaxOutput("32K");
        shotA.setReasoning("HIGH");
        ModelMeta shotB = new ModelMeta("model-b");
        shotB.setImage(true);
        AppSettings.get().getActiveProfile().setModelMetas(List.of(shotA, shotB));
        AppSettings.get().getActiveProfile().setModel("model-a");
        edt(() -> {
            dialogHolder = new SettingsDialog(WINDOW, false);
            dialogHolder.setVisible(true);
        });
        Thread.sleep(600);
        boolean[] modelDeleteEnabled = new boolean[1];
        edt(() -> modelDeleteEnabled[0] = dialogHolder.isModelRowDeleteEnabled(0));
        assertTrue(modelDeleteEnabled[0], "打开设置行删除钮即可用（目录两条）");
        captureDialogGated("window-5-settings.png", "设置",
                "设置面板默认页=模型服务：后端地址/模型目录两条/温度等参数表单。改动请只看不保存——「保存」会写进你机器的真实偏好。");

        // —— 设置面板分页断言（计划外）：默认页=模型服务；两枚胶囊来回切，各自页内控件在/不在。
        //  用 uiHasShowingTextAnyWindow：CardLayout 隐藏页的组件仍在容器树里，不带显隐过滤会误报 ——
        assertTrue(uiHasShowingTextAnyWindow("配置方案") && uiHasShowingTextAnyWindow("API 类型"), "默认页=模型服务页");
        assertFalse(uiHasShowingTextAnyWindow("正文字号"), "模型服务页不含全局项");
        edt(() -> uiClickAnyWindow("通用"));
        Thread.sleep(400);
        assertTrue(uiHasShowingTextAnyWindow("正文字号") && uiHasShowingTextAnyWindow("主题")
                && uiHasShowingTextAnyWindow("日志等级"), "切到通用页（全局项在）");
        assertTrue(uiHasShowingTextAnyWindow("审核到来时播放系统提示音")
                && uiHasShowingTextAnyWindow("审核到来时弹系统通知（托盘气泡）")
                && uiHasShowingTextAnyWindow("审核到来时窗口抢到前台"), "通用页审核通知三开关在");
        assertFalse(uiHasShowingTextAnyWindow("配置方案"), "通用页不含配置方案控件");
        captureDialogGated("window-5b-settings-general.png", "设置", "通用页：正文字号/主题/日志等级 + 审核通知三开关的表单形态。");
        // 工具页：注册表直连读（ToolCatalog 包 catch(Throwable)），切页断言只看不依赖注册表可见性的两侧
        edt(() -> uiClickAnyWindow("工具"));
        Thread.sleep(400);
        assertFalse(uiHasShowingTextAnyWindow("配置方案") && uiHasShowingTextAnyWindow("正文字号"),
                "工具页不含其他页控件");
        captureDialogGated("window-5d-settings-tools.png", "设置",
                "工具页：ToolManager 注册的工具逐条一行，勾选框=声明给模型（本次跑全量依赖，是真实注册行）。勾选态看看就好，别点「保存」。");
        edt(() -> uiClickAnyWindow("模型服务"));
        Thread.sleep(400);
        assertTrue(uiHasShowingTextAnyWindow("配置方案"), "切回模型服务页");
        // 展开首条：参数区出现（收起态只有名字行）——目录的参数行随"展开/收起"钮切换
        edt(() -> uiClickAnyWindow("展开"));
        Thread.sleep(300);
        assertTrue(uiHasShowingTextAnyWindow("上下文") && uiHasShowingTextAnyWindow("最大输出"),
                "展开后上下文/最大输出在");
        assertTrue(uiHasShowingTextAnyWindow("推理等级") && uiHasShowingTextAnyWindow("文本")
                && uiHasShowingTextAnyWindow("图片"), "展开后推理等级/输入能力在");
        captureDialogGated("window-5c-settings-catalog.png", "设置",
                "模型服务页展开首条目录：上下文/最大输出/推理等级/输入能力的参数区随「展开」钮打开。");
        edt(() -> dialogHolder.dispose());
        // 还原模型目录（改动只在内存，给后续深色/托盘段留原样）
        AppSettings.get().getActiveProfile().setModelMetas(oldMetas);
        AppSettings.get().getActiveProfile().setModel(oldModel);
    }

    @Test
    @Order(7)
    void s07_markdownSession() throws Exception {
        // —— 截图 6：Markdown 渲染（标题/行内样式/代码块/列表/引用/分隔线/表格） ——
        // 切会话会自动滚到底，先滚回顶部让卡片开头的标题入镜
        edt(() -> WINDOW.getStore().switchTo(c3.getId()));
        Thread.sleep(1500);
        scrollWaterfallToTop();
        Thread.sleep(400);
        captureGated("window-6-markdown.png",
                "Markdown 渲染全景：加粗/斜体/行内代码/删除线/链接、表格、代码块着色、列表（有序/无序/嵌套/待办）、引用、分隔线、diff 增删行着色。");
        // 用户消息复制（用户卡尾部"复制"钮）：uiClick 按树序先碰用户卡，剪贴板应是用户原文
        edt(() -> uiClick("复制"));
        Thread.sleep(200);
        assertEquals("给我演示一下 Markdown 渲染", clipboardText(), "用户消息复制到剪贴板");
        // 链接跳转（P1-6）：显示中正文 pane 的链接字符带 url 属性，modelToView 反推坐标能命中
        assertTrue(uiLinkHit("https://example.com/path?q=1"), "链接字符带 url 属性且命中检测可取回");
        // 划词复制（计划外）：显示组件可聚焦可选中，Ctrl+C 拷选区而非整卡
        selectAndCtrlC("给我演示一下 Markdown 渲染", 0, 4);
        Thread.sleep(400); // 选区重绘是异步的，等一拍再截，不然截图里没有高亮
        captureGated("window-18-selection.png", "划词选区：卡片文字被选中且高亮可见（自动化窗口可能不在前台，看得到高亮即正常）。");
        Thread.sleep(200);
        assertEquals("给我演示", clipboardText(), "划词 Ctrl+C 复制选区");
        // 会话内搜索（Ctrl+F）：开搜索条填词，匹配计数与定位跳转；收起后入口消失
        edt(() -> WINDOW.openSearch("Markdown"));
        Thread.sleep(400);
        captureGated("window-19-search.png",
                "会话内搜索条：填词后计数「共 N 处」并自动跳到第一处命中——命中卡的边框亮主题色闪 700ms（文字本身不做高亮；侧栏那种「高亮」是标题命中加粗，形态不同）。"
                        + "请顺手试：点几下「下一个」看逐站跳动，改动搜索词看计数实时变化。");
        assertTrue(uiHasShowingText("上一个") && uiHasShowingText("下一个") && uiHasShowingText("收起"), "搜索条打开");
        assertTrue(uiHasLabelContaining(" / "), "命中计数与游标显示");
        edt(() -> WINDOW.closeSearch());
        Thread.sleep(300);
        assertFalse(uiHasShowingText("收起"), "搜索条收起");
    }

    @Test
    @Order(8)
    void s08_streaming() throws Exception {
        // —— 截图 7：流式输出（appendChunk/appendThinking 双通道节流渲染 + 独立思考区 + 自动滚底） ——
        // 思考走独立通道：流式中独立思考区展开增长，收尾后收起显示时长；正文全程无思考标记拼接
        String streamedBody = "### 流式输出\n\n这是**逐段**追加的回复，"
                + "每 120ms 渲染一次，`高度`自适应。\n\n```java\nint x = 42;\n```\n";
        Window.AiStream[] streamHolder = new Window.AiStream[1];
        edt(() -> streamHolder[0] = WINDOW.addAiMessage(""));
        Thread.sleep(500);
        edt(() -> streamHolder[0].appendThinking("先拆解一下问题再作答。\n思考区与正文各走各的缓冲。\n"));
        Thread.sleep(300);
        assertTrue(uiHasTextPaneContaining("先拆解一下问题再作答"), "思考增量直接进独立思考区，思考中展开可见");
        // 乱序容忍：思考没收尾正文先到，思考还能继续追加，两边互不干扰
        edt(() -> streamHolder[0].appendChunk("### 流式输出\n\n这是**逐段**追加的回复，"));
        edt(() -> streamHolder[0].appendThinking("正文先到，思考继续追加。\n"));
        Thread.sleep(300);
        edt(() -> streamHolder[0].finishThinking());
        Thread.sleep(300);
        assertTrue(uiHasLabelContaining("已思考") && uiHasLabelContaining("秒"), "思考收尾收起为一行并显示时长");
        edt(() -> uiClickNamed("thinking-header"));
        Thread.sleep(300);
        assertTrue(uiHasTextPaneContaining("先拆解一下问题再作答"), "点头部展开已收起的思考独立区");
        edt(() -> uiClickNamed("thinking-header"));
        Thread.sleep(300);
        assertFalse(uiHasTextPaneContaining("先拆解一下问题再作答"), "再点收回");
        edt(() -> streamHolder[0].appendChunk("每 120ms 渲染一次，`高度`自适应。\n\n```java\nint x = 42;\n```\n"));
        Thread.sleep(600);
        captureGated("window-7-streaming.png", "流式输出卡：思考区已收成一行「已思考 N 秒」，正文含加粗/行内代码/代码块；后面的收尾会落库。");
        edt(() -> streamHolder[0].finish());
        Message streamedMessage = lastMessage();
        assertEquals(streamedBody, streamedMessage.text(), "正文落库干净（无思考标记拼接）");
        assertTrue(streamedMessage.thinking() != null
                        && streamedMessage.thinking().contains("先拆解一下问题再作答")
                        && streamedMessage.thinking().contains("正文先到，思考继续追加"),
                "思考独立落库（含乱序后到的那一段）");
        assertFalse(streamedMessage.text().contains("拆解"), "正文字段不含思考文本（混杂问题消除）");
        List<Message> reloadedMessages = reload().getActive().getMessages();
        Message reloadedStreamed = reloadedMessages.get(reloadedMessages.size() - 1);
        assertTrue(reloadedStreamed.thinking() != null
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
        assertTrue(uiHasLabelContaining("思考中"), "断流前思考区处于展开的思考中态");
        edt(() -> brokenHolder[0].finish()); // 等价于 Main 的 finally 分支，正文为空
        Thread.sleep(300);
        assertTrue(uiHasLabelContaining("已思考") && uiHasLabelContaining("秒"),
                "断流后 finish() 兜底把思考区收成已思考一行（不会永停思考中）");
        assertFalse(uiHasLabelContaining("思考中"), "收尾后不再显示思考中");
        Message brokenMessage = lastMessage();
        assertTrue("".equals(brokenMessage.text()) && brokenMessage.thinking() != null
                        && brokenMessage.thinking().contains("正文还没到就断流"),
                "空正文 + 思考独立落库");
    }

    @Test
    @Order(9)
    void s09_toolCard() throws Exception {
        // —— agent 工具调用卡（契约：addToolCall(名, 参数摘要) → appendArgs(增量) 流式显示 → finish(结果, 是否成功, 完整参数)） ——
        // 截图 16：一张运行中只显示了一半参数 + 一张完成；跑完断言落库与落盘往返
        Conversation[] toolConvHolder = new Conversation[1];
        edt(() -> toolConvHolder[0] = store.newConversation());
        Thread.sleep(1000); // fireChanged 已把主区切到新会话
        Window.ToolCallHandle[] toolHolder = new Window.ToolCallHandle[1];
        edt(() -> toolHolder[0] = WINDOW.addToolCall("read_file", "", "call_2217929885810011"));
        edt(() -> toolHolder[0].appendArgs("{\"path\": "));
        Thread.sleep(200);
        edt(() -> toolHolder[0].appendArgs("\"src/Demo."));
        Thread.sleep(200);
        captureGated("window-16-tool-card.png", "工具调用卡「运行中…」状态：参数 JSON 正在流式追加（此刻只有一半就对了，另一半会补上）。"); // "运行中…"状态，参数已流式显示出一半
        edt(() -> toolHolder[0].appendArgs("java\"}"));
        Thread.sleep(200);
        // finish 传空参数：终态参数应回退到流式累积的完整 JSON（argsByCallId 兜底路径）
        edt(() -> toolHolder[0].finish("public class Demo {\n    private Buffer buffer;\n}", true, ""));
        edt(() -> {
            Window.ToolCallHandle handle = WINDOW.addToolCall("read_file", "{\"path\": \"missing.txt\"}",
                    "call_2217929885810012");
            handle.finish("错误: 文件不存在", false, "{\"path\": \"missing.txt\"}");
        });
        Thread.sleep(400);
        assertEquals(2, store.getActive().getMessages().size(), "工具卡消息数");
        assertTrue(store.getActive().getMessages().get(0).isToolCall()
                && "ok".equals(store.getActive().getMessages().get(0).toolStatus())
                && store.getActive().getMessages().get(0).text().contains("Demo"), "工具结果落库");
        assertEquals("{\"path\": \"src/Demo.java\"}", store.getActive().getMessages().get(0).toolArgs(),
                "流式参数累积后 finish 空参回退落库");
        assertEquals("error", store.getActive().getMessages().get(1).toolStatus(), "失败态落库");
        Message reloadedTool = reload().getActive().getMessages().get(0);
        assertTrue(reloadedTool.isToolCall() && "read_file".equals(reloadedTool.toolName())
                && "{\"path\": \"src/Demo.java\"}".equals(reloadedTool.toolArgs())
                && "ok".equals(reloadedTool.toolStatus()), "工具消息落盘往返");
        // 工具卡折叠（P1-4）：默认收成一行，点头部展开出结果与复制入口，再点收回
        assertFalse(uiHasTextAreaStartingWith("public class Demo"), "工具卡默认折叠");
        edt(() -> uiClickNamed("tool-header"));
        Thread.sleep(300);
        assertTrue(uiHasTextAreaStartingWith("public class Demo"), "点头部展开结果");
        assertTrue(uiHasText("复制结果") && uiHasText("复制参数"), "展开后有复制入口");
        captureGated("window-16b-tool-expanded.png", "工具卡展开态：结果文本区可见，尾部有「复制结果/复制参数」两个入口（下一个会收回）。");
        edt(() -> uiClickNamed("tool-header"));
        Thread.sleep(300);
        assertFalse(uiHasTextAreaStartingWith("public class Demo"), "再点头部收回");
    }

    @Test
    @Order(10)
    void s10_approvalCard() throws Exception {
        // —— 权限确认卡（P0-4 契约：requestApproval(future) → 卡片"待确认"+允许/拒绝 → 点击写 future 并回到运行中） ——
        // 截图 17：待确认卡。待确认是纯显示态不落库，断言看视图树与 future，落库断言看决完之后的终态
        java.util.concurrent.CompletableFuture<Boolean>[] decision = new java.util.concurrent.CompletableFuture[1];
        edt(() -> {
            Window.ToolCallHandle approvalHandle = WINDOW.addToolCall("write_file", "{\"path\": \"a.txt\"}",
                    "call_approval_1");
            decision[0] = new java.util.concurrent.CompletableFuture<>();
            approvalHandle.requestApproval(decision[0]);
            APPROVAL_HOLDER[0] = approvalHandle;
        });
        Thread.sleep(300);
        assertTrue(uiHasText("待确认") && uiHasText("允许") && uiHasText("拒绝"), "待确认态徽标与按钮");
        assertTrue(sidebarBadgeHtml(store.getActive().getId()).contains(">待确认<"), "侧栏亮\"待确认\"标注");
        captureGated("window-17-approval.png",
                "权限确认卡：头部「待确认」徽标 + 允许/拒绝按钮；侧栏该会话亮「待确认」。注意：别点卡片上的按钮——下一段自动化要点「允许」走允许路径，你先点了它就找不到了。");
        edt(() -> uiClick("允许"));
        Thread.sleep(300);
        assertTrue(Boolean.TRUE.equals(decision[0].getNow(null)), "允许按钮写入 future");
        assertFalse(sidebarBadgeHtml(store.getActive().getId()).contains(">待确认<"), "审批决出侧栏标注熄灭");
        assertTrue(!uiHasText("待确认") && !uiHasText("允许") && uiHasText("运行中…"), "放行后回到运行中态");
        edt(() -> APPROVAL_HOLDER[0].finish("写入完成", true, "{\"path\": \"a.txt\"}"));
        // 拒绝路径：按钮写 false，随后按 provider 的收尾（DENIED_RESULT, 失败）落库
        java.util.concurrent.CompletableFuture<Boolean>[] denied = new java.util.concurrent.CompletableFuture[1];
        edt(() -> {
            Window.ToolCallHandle denyHandle = WINDOW.addToolCall("write_file", "{\"path\": \"b.txt\"}",
                    "call_approval_2");
            denied[0] = new java.util.concurrent.CompletableFuture<>();
            denyHandle.requestApproval(denied[0]);
            DENY_HOLDER[0] = denyHandle;
        });
        Thread.sleep(300);
        edt(() -> uiClick("拒绝"));
        Thread.sleep(300);
        assertTrue(Boolean.FALSE.equals(denied[0].getNow(null)), "拒绝按钮写入 future");
        edt(() -> DENY_HOLDER[0].finish("用户拒绝了本次工具调用，工具未执行。", false, "{\"path\": \"b.txt\"}"));
        // 已结束的卡片再请求审批：必须立刻决成 false，不能让等待方吊死
        java.util.concurrent.CompletableFuture<Boolean>[] late = new java.util.concurrent.CompletableFuture[1];
        edt(() -> {
            late[0] = new java.util.concurrent.CompletableFuture<>();
            APPROVAL_HOLDER[0].requestApproval(late[0]);
        });
        assertTrue(Boolean.FALSE.equals(late[0].getNow(null)), "终态卡请求审批立即拒绝");
        Thread.sleep(300);
        int toolBase = store.getActive().getMessages().size() - 4; // 前两张工具卡 + 本段两张
        assertTrue("ok".equals(store.getActive().getMessages().get(toolBase + 2).toolStatus())
                && "error".equals(store.getActive().getMessages().get(toolBase + 3).toolStatus()),
                "允许/拒绝终态落库");
    }

    @Test
    @Order(11)
    void s11_narrowWindowApproval() throws Exception {
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
        java.util.concurrent.CompletableFuture<Boolean>[] narrowDecision = new java.util.concurrent.CompletableFuture[1];
        edt(() -> {
            Window.ToolCallHandle narrowHandle = WINDOW.addToolCall("write_file", longArgs, "call_narrow");
            narrowDecision[0] = new java.util.concurrent.CompletableFuture<>();
            narrowHandle.requestApproval(narrowDecision[0]);
            NARROW_HOLDER[0] = narrowHandle;
        });
        Thread.sleep(300);
        edt(() -> WINDOW.setSize(760, narrowOldSize[1])); // 缩到 760 宽：老布局下按钮已被裁出卡外
        Thread.sleep(600); // 等 resize 触发的 fitAllCardHeights 重排
        assertTrue(buttonFullyVisible("允许"), "窄窗口下允许按钮完整可见（未被裁出行外）");
        // 展开参数全文：点卡片树里最后一个 tool-header（DFS 顺序末位 = 最新的待确认卡）
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            javax.swing.JPanel target = null;
            for (Component component : all) {
                if (component instanceof javax.swing.JPanel jPanel && "tool-header".equals(jPanel.getName())) {
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
        assertTrue(uiHasTextAreaStartingWith("{\"path\": \"workspace/very/long"), "展开显示参数全文");
        humanGate("窄窗口审批（无截图）：窗口缩到 760 宽后，长参数待确认卡头部的「允许」按钮仍完整可见、不是被裁出行外；展开区能看到参数全文。");
        edt(() -> uiClick("允许")); // 点击落到按钮上 future 才会被写——顺带证明窄窗口下可点
        Thread.sleep(300);
        assertTrue(Boolean.TRUE.equals(narrowDecision[0].getNow(null)), "窄窗口下允许按钮仍可点击");
        edt(() -> NARROW_HOLDER[0].finish("写入完成", true, longArgs)); // 收尾：不留待确认标注
        edt(() -> WINDOW.setSize(narrowOldSize[0], narrowOldSize[1])); // 还原尺寸（防抖落盘存回原值）
        Thread.sleep(600);
    }

    @Test
    @Order(12)
    void s12_hoverMenu() throws Exception {
        // 工具卡演示把主区切到了新会话，切回 Markdown 样例会话供后面 8 号悬停菜单截图用
        edt(() -> WINDOW.getStore().switchTo(c3.getId()));
        Thread.sleep(800);
        // —— 截图 8：悬停"…"菜单（合成 mouseEntered 让浮层显示，不做真实鼠标移动） ——
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof javax.swing.JPanel) {
                    component.dispatchEvent(new MouseEvent(component,
                            MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, 5, 5, 0, false));
                }
            }
        });
        Thread.sleep(400);
        captureGated("window-8-hover.png", "悬停菜单：卡片右上角「…」浮层在合成 mouseEntered 后显示。请顺手试：真实把鼠标划过几张卡片看「…」与合成效果是否一致。");
    }

    @Test
    @Order(13)
    void s13_pinSearchAndContextMenu() throws Exception {
        // —— 截图 9：置顶排序（c2 置顶后跳到列表最前，时间行带"置顶"标签；先把 4 号折叠的侧栏展开回来） ——
        edt(() -> {
            WINDOW.getSidebar().setExpanded(true);
            WINDOW.getSplitPane().setDividerLocation(Sidebar.EXPANDED_WIDTH);
            WINDOW.getContentPane().revalidate();
        });
        Thread.sleep(400);
        edt(() -> store.setPinned(c2.getId(), true));
        Thread.sleep(400);
        captureGated("window-9-pinned.png", "置顶排序：c2 置顶后跳到列表最前，时间行带「置顶」标签（侧栏已从折叠态展开回来）。");
        // —— 截图 10：搜索标题命中高亮（"今天"命中 c2 标题） ——
        edt(() -> WINDOW.getSidebar().setSearchQuery("今天"));
        Thread.sleep(400);
        captureGated("window-10-search-highlight.png", "侧栏搜索「今天」：标题命中的 c2 条目高亮。");
        // —— 截图 11：搜索内容命中片段（"并发"只在 c1 内容里，条目显示片段预览行） ——
        edt(() -> WINDOW.getSidebar().setSearchQuery("并发"));
        Thread.sleep(400);
        captureGated("window-11-search-snippet.png", "侧栏搜索「并发」：只在 c1 内容命中，条目带片段预览行。");
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
        captureGated("window-12-context-menu.png", "右键菜单：对会话条目合成 popupTrigger 后弹出的菜单。请顺手试：真实右键一条会话比对菜单形态（下一个会收掉菜单）。");
        // —— 收尾：合成 MOUSE_EXITED 藏掉 8 号起的悬停"…"，clearSelectedPath 关掉右键菜单 ——
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(WINDOW, all);
            for (Component component : all) {
                if (component instanceof javax.swing.JPanel) {
                    component.dispatchEvent(new MouseEvent(component,
                            MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, 5, 5, 0, false));
                }
            }
            MenuSelectionManager.defaultManager().clearSelectedPath();
        });
        Thread.sleep(400);
    }

    @Test
    @Order(14)
    void s14_longConversationWindowing() throws Exception {
        // —— 长会话窗口化（P2-2）：300 条消息，卡片全量占位、只物化视口附近的组件 ——
        // 分批灌入让窗口保持响应，避免整块 EDT 长时间冻结（store 操作已挂在窗口监听，全部进 EDT）
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
        assertEquals(300, counters[0], "长会话卡片全量占位: " + counters[0]);
        assertTrue(counters[1] > 10 && counters[1] < 120, "窗口化后仅视口附近物化: " + counters[1]);
        // 滚回顶部（走滚动条 setValue，与真实用户拖拽同路径）：首卡应被物化，组件总数仍受限
        edt(() -> {
            Container viewportParent = WINDOW.getWaterfallPanel().getParent().getParent();
            if (viewportParent instanceof javax.swing.JScrollPane scrollPane) {
                scrollPane.getVerticalScrollBar().setValue(0);
            }
        });
        Thread.sleep(600);
        edt(() -> {
            counters[0] = ((Container) WINDOW.getWaterfallPanel().getComponent(0)).getComponentCount();
            counters[1] = countTextPanes(WINDOW);
        });
        assertTrue(counters[0] > 0, "滚回顶部后首卡已物化");
        assertTrue(counters[1] < 120, "滚动后物化窗口仍受限: " + counters[1]);
        captureGated("window-13-long-conversation.png",
                "长会话窗口化：滚回顶部后首卡已物化（300 张卡不全量物化，只有视口附近的文本组件）。请顺手试：真实滚动滚轮拉到底再滚回来，看卡顿与否。");
    }

    @Test
    @Order(15)
    void s15_darkTheme() throws Exception {
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
        captureGated("window-14-dark.png", "深色主题：整窗原地重建后的配色（侧栏/卡片/思考块/代码块/引用/表格都随深色，下一个会打开深色设置面板）。");

        // —— 截图 15：深色下的设置面板（主题下拉应显示"深色"，输入控件跟随弹层配色） ——
        edt(() -> {
            dialogHolder = new SettingsDialog(WINDOW, false);
            dialogHolder.setVisible(true);
        });
        // 勾上"最小化到托盘"，顺带验证选中态的对勾图标在深色下的渲染
        edt(() -> {
            List<Component> all = new ArrayList<>();
            collectComponents(dialogHolder, all);
            for (Component component : all) {
                if (component instanceof JCheckBox box) {
                    box.setSelected(true);
                }
            }
        });
        Thread.sleep(600);
        captureDialogGated("window-15-dark-settings.png", "设置", "深色下的设置面板：主题下拉显示「深色」，输入控件跟随弹层配色，全部勾选框打勾验证选中态对勾图标。");
        edt(() -> dialogHolder.dispose());
        // 给后续用例恢复浅色基线（截图配色只在内存）
        edt(() -> {
            AppSettings.get().setUiTheme("light");
            WINDOW.retheme();
        });
        Thread.sleep(1200);
    }

    @Test
    @Order(16)
    void s16_trayMinimize() throws Exception {
        // —— 托盘最小化（P2-3）：关按钮=隐藏到托盘而非退出 ——
        assumeFalse(GraphicsEnvironment.isHeadless() || !SystemTray.isSupported(),
                "无托盘环境：跳过托盘最小化回归");
        SystemTray tray = SystemTray.getSystemTray();
        boolean oldTrayPref = AppSettings.get().isMinimizeToTray();
        edt(() -> {
            // 前面建窗时设过 DISPOSE_ON_CLOSE，这里换回窗口自管的 DO_NOTHING 再验关闭路径
            WINDOW.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            // 关闭行为现在实时读 AppSettings 的偏好（托盘图标常驻注册），先把偏好打开
            AppSettings.get().setMinimizeToTray(true);
            WINDOW.setMinimizeToTray(true);
        });
        assertEquals(1, tray.getTrayIcons().length, "托盘图标已注册");
        edt(() -> WINDOW.dispatchEvent(new WindowEvent(WINDOW, WindowEvent.WINDOW_CLOSING)));
        Thread.sleep(400);
        assertFalse(WINDOW.isVisible(), "关闭按钮最小化到托盘（窗口隐藏）");
        assertTrue(WINDOW.isDisplayable(), "窗口未销毁（托盘可恢复）");
        edt(() -> {
            AppSettings.get().setMinimizeToTray(oldTrayPref); // 恢复真实偏好
            WINDOW.setVisible(true);
        });
        assertEquals(1, tray.getTrayIcons().length, "托盘图标常驻（不随偏好注销）");
        humanGate("托盘最小化演示已完成：点关闭后窗口收进托盘再恢复显示（本机 SystemTray 支持时才走到这里；无托盘环境整段自动跳过）。");
    }

    @Test
    @Order(17)
    void s17_workspaceDialog() throws Exception {
        // —— 截图 20：新建对话的工作区弹窗（计划外）。模态弹窗：开窗的点击用 invokeLater 派发
        // （不等待——edt 会卡在 doClick 里等弹窗关闭），EDT 进模态泵后仍派发后续任务；
        // 关窗的点击走 edt，弹窗关闭后 doClick 才返回 ——
        int conversationsBefore = store.getConversations().size();
        Files.createDirectories(AppSettings.defaultWorkspace()); // 默认目录先在着，确定时不触发"是否创建"确认
        SwingUtilities.invokeLater(() -> uiClickAnyWindow("＋ 新对话"));
        Thread.sleep(800);
        assertTrue(uiHasTextAnyWindow("新建对话的工作区——工具的相对路径以此为基准"), "工作区弹窗打开");
        assertTrue(uiHasTextAnyWindow("浏览…"), "弹窗里有浏览按钮（资源管理器选目录）");
        captureDialogGated("window-20-workspace.png", "选择工作区",
                "新建对话的工作区弹窗：说明文案 + 「浏览…」按钮 + 路径输入（下一个自动点「取消」，不新建会话）。这站弹窗是模态的，主窗外点不了属正常，也没有顺手试环节。");
        edt(() -> uiClickAnyWindow("取消"));
        Thread.sleep(300);
        assertEquals(conversationsBefore, store.getConversations().size(), "取消不新建会话");
        // 确定默认路径：新建会话 workspace 保持 null（默认不落到会话上，recents 也不记默认）
        SwingUtilities.invokeLater(() -> uiClickAnyWindow("＋ 新对话"));
        Thread.sleep(800);
        edt(() -> uiClickAnyWindow("开始对话"));
        Thread.sleep(600);
        assertEquals(conversationsBefore + 1, store.getConversations().size(), "确定后新建会话");
        assertNull(store.getActive().getWorkspace(), "默认工作区不落到会话");
        assertTrue(sidebarGroupRows().stream().anyMatch(g -> "默认工作区".equals(g[0]) && g[1] == null),
                "新建默认会话后默认分组仍在");

        // —— 侧栏分组跟会话 + 组头加号（原"工作区显示条跟会话"+"复制路径"随条移除） ——
        Files.createDirectories(Path.of(wsPath)); // 目录先在着，确定时不触发"是否创建"确认弹窗
        SwingUtilities.invokeLater(() -> uiClickAnyWindow("＋ 新对话"));
        Thread.sleep(800);
        edt(() -> uiTypeWorkspacePath(wsPath));
        edt(() -> uiClickAnyWindow("开始对话"));
        Thread.sleep(600);
        assertEquals(wsPath, store.getActive().getWorkspace(), "弹窗选定的工作区落到会话");
        String wsName = Path.of(wsPath).getFileName().toString();
        assertTrue(sidebarGroupRows().stream().anyMatch(g -> wsName.equals(g[0]) && wsPath.equals(g[1])),
                "绑定的工作区在侧栏有自己的分组");
        humanGate("弹窗选定的工作区已落会话：主区会话绑定到 ws-project 目录，侧栏出现 ws-project 分组、组下挂着新会话。");
    }

    @Test
    @Order(18)
    void s18_groupHeaderPlusAndCollapse() throws Exception {
        String wsName = Path.of(wsPath).getFileName().toString();
        // 组头加号：跳过弹窗直接新建；空会话复用语义生效（当前空对话改绑，不堆空会话）
        int sizeBeforePlus = store.getConversations().size();
        edt(() -> WINDOW.getSidebar().newConversationInGroup(null)); // 默认组加号：null 绑定
        Thread.sleep(400);
        assertTrue(store.getConversations().size() == sizeBeforePlus
                && store.getActive().getWorkspace() == null, "默认组加号：空会话改绑默认不新建");
        edt(() -> WINDOW.getSidebar().newConversationInGroup(wsPath)); // 绑定组加号
        Thread.sleep(400);
        assertTrue(store.getConversations().size() == sizeBeforePlus
                && wsPath.equals(store.getActive().getWorkspace()), "绑定组加号：空会话改绑组工作区");
        assertTrue(sidebarGroupRows().stream().anyMatch(g -> wsName.equals(g[0]) && wsPath.equals(g[1])),
                "加号新建后绑定分组仍在");
        // —— 组头折叠/展开：点组头收起只留头行（搜索激活时忽略收起态，这里的 query 为空） ——
        long defaultGroupConvs = store.getConversations().stream()
                .filter(c -> c.getWorkspace() == null || c.getWorkspace().isBlank()).count();
        int rowsBeforeCollapse = sidebarRowCount();
        edt(() -> WINDOW.getSidebar().toggleGroupCollapsed(null)); // 折叠默认组
        Thread.sleep(400);
        assertTrue(sidebarRowCount() == rowsBeforeCollapse - defaultGroupConvs, "收起组：只留组头，其余随组收掉");
        captureGated("window-20c-group-collapsed.png", "组头折叠：默认工作区组收起后只留组头一行，组下会话全部隐藏（下一个会展开）。");
        edt(() -> WINDOW.getSidebar().toggleGroupCollapsed(null)); // 再点展开
        Thread.sleep(400);
        assertTrue(sidebarRowCount() == rowsBeforeCollapse, "再点组头展开恢复");
        captureGated("window-20b-workspace-groups.png", "组头展开恢复 + 工作区分组全景：默认工作区组与 ws-project 组并存，组头有「＋」直达新建。");
    }

    @Test
    @Order(19)
    void s19_inputHeightGrow() throws Exception {
        // —— 输入框高度自适应（计划外）：多行先增高，到上限（240）后不再长、由内部滚动接管
        //  （长内容曾只能看见首两行，新打的字"藏在视口外"），清空回落到初始高度 ——
        edt(() -> WINDOW.getInputTextArea().setText("第一行\n第二行\n第三行"));
        Thread.sleep(300);
        int grownHeight = WINDOW.getInputPreferredHeight();
        assertTrue(grownHeight > 48, "多行输入增高（超过原固定高 48）");
        StringBuilder manyLines = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            manyLines.append("第").append(i + 1).append("行\n");
        }
        edt(() -> WINDOW.getInputTextArea().setText(manyLines.toString()));
        Thread.sleep(400);
        assertEquals(240, WINDOW.getInputPreferredHeight(), "触帽后不再增高（内部滚动接管）");
        captureGated("window-21-input-grow.png", "输入框触帽内滚：多行输入到 240 上限后不再增高、内部滚动接管。请顺手试：真实打几行字看高度自适应（下一个会清空回落初始高 48）。");
        edt(() -> WINDOW.getInputTextArea().setText(""));
        Thread.sleep(400);
        assertEquals(48, WINDOW.getInputPreferredHeight(), "清空后回落到初始高度（不只长不缩）");
        assertEnterKeyMods();
    }

    /**
     * 输入框回车键位（换行需求）：纯回车发送；Shift+回车 / Ctrl+回车 / Shift+Ctrl+回车
     * 不发送、由自己往光标处插 \n（默认 binding 对修饰回车不可靠，见踩坑 #32）。合成
     * KeyEvent 走真实监听器路径。**不依赖"会话已有消息"**：活跃会话在此刻可能是空的，
     * 发送与否按消息条数差值断言。
     */
    private static void assertEnterKeyMods() throws Exception {
        int before = store.getActive().getMessages().size();
        // Ctrl+回车：插一个 \n，不发送
        edt(() -> WINDOW.getInputTextArea().dispatchEvent(pressedEnter(KeyEvent.CTRL_DOWN_MASK)));
        Thread.sleep(100);
        assertEquals(before, store.getActive().getMessages().size(), "Ctrl+回车不该发送（留给换行）");
        assertEquals("\n", WINDOW.getInputTextArea().getText(), "Ctrl+回车在光标处插换行");
        // 收掉再试 Shift+Ctrl+回车：同上
        edt(() -> WINDOW.getInputTextArea().setText(""));
        edt(() -> WINDOW.getInputTextArea().dispatchEvent(pressedEnter(
                KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK)));
        Thread.sleep(100);
        assertEquals(before, store.getActive().getMessages().size(), "Shift+Ctrl+回车不该发送（留给换行）");
        assertEquals("\n", WINDOW.getInputTextArea().getText(), "Shift+Ctrl+回车在光标处插换行");
        // 纯回车：真实发送路径吃下多行文本，消息原文带换行落库
        String sent = "第一行\n第二行";
        edt(() -> WINDOW.getInputTextArea().setText(sent));
        edt(() -> WINDOW.getInputTextArea().dispatchEvent(pressedEnter(0)));
        Thread.sleep(200);
        List<Message> messages = store.getActive().getMessages();
        assertEquals(before + 1, messages.size(), "纯回车应新增一条消息");
        assertEquals(sent, messages.get(messages.size() - 1).text(), "多行文本原样落库");
        assertEquals("", WINDOW.getInputTextArea().getText(), "发送后输入框清空");
        // 收尾清场：恢复输入框为空（多行草稿不留在窗口里）
        edt(() -> WINDOW.getInputTextArea().setText(""));
    }

    /** 合成一次"按下回车"KeyEvent（modifiers 传 0 或 Shift/Ctrl 掩码） */
    private static KeyEvent pressedEnter(int modifiers) {
        return new KeyEvent(WINDOW.getInputTextArea(), KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), modifiers, KeyEvent.VK_ENTER, '\r');
    }

    // —— 断言外的公共小工具：默认模型/思考强度/权限模式非空 & 最后消息 & 重新读盘 ——

    private static void assertInputDefaults() throws Exception {
        edt(() -> {
            assertNotNull(WINDOW.getSelectedModel(), "输入区默认模型非空");
            assertNotNull(WINDOW.getSelectedEffort(), "输入区默认思考强度非空");
            assertNotNull(WINDOW.getSelectedMode(), "输入区默认权限模式非空");
        });
    }

    /** 当前活跃会话的最后一条消息（工具卡/流式落库断言用） */
    private static Message lastMessage() {
        List<Message> messages = store.getActive().getMessages();
        return messages.get(messages.size() - 1);
    }

    /** 重新读磁盘上的 shot.json（落盘往返断言用） */
    private static ConversationStore reload() {
        return new ConversationStore(storePath);
    }

    // —— 悬停/点击/截图等 UI 断言帮助方法（原 Shot.java 一字未动平移） ——

    private static void edt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void capture(String name) throws Exception {
        Path path = SCREENS.resolve(name);
        edt(() -> {
            BufferedImage image = new BufferedImage(WINDOW.getWidth(), WINDOW.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            WINDOW.printAll(image.createGraphics());
            try {
                ImageIO.write(image, "png", new File(path.toString()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 截图 + 交互向导门：全自动模式只截图；向导模式（见类注释）在截图后弹主题化说明卡等人确认，
     * 说明卡期间画面正处该步的可观察态。人工终止后剩余观察点静默自动跑完。
     */
    private static void captureGated(String name, String what) throws Exception {
        capture(name);
        humanGate(what);
    }

    /** 同 captureGated，作用于按标题寻_dialog 的弹窗截图（工作区弹窗/设置面板） */
    private static void captureDialogGated(String name, String dialogTitle, String what) throws Exception {
        captureDialog(name, dialogTitle);
        humanGate(what);
    }

    /**
     * 交互向导门，两种形态自适应：
     * <ul>
     *   <li><b>常态 = 非模态说明卡</b>：MODELESS + alwaysOnTop，钉在主窗右下角——用户在等的
     *       期间可以随意点被测窗口（hover/右键/打字/拖分隔条，站点的"请顺手试"都在这条腿上），
     *       测试线程 latch.await() 等待，点「下一个」countDown；「终止」置 abort 同样放行。</li>
     *   <li><b>真模态弹窗在场 → 退回模态卡</b>（Ui.confirmDialog）：Swing 模态规则是显示中的
     *       APPLICATION_MODAL 弹窗把事后创建的窗也锁进模态作用域，非模态卡会被拦死点不了
     *       （工作区弹窗那站踩过）。</li>
     * </ul>
     * 自动过站档（-Dgui.interactive.auto=true）：卡照弹，600ms 后 Swing Timer 在向导卡
     * 里找「下一个」钮合成 doClick（真实按钮路径；只扫标题"GUI 回归向导"开头的卡，
     * 产品搜索条有同名按钮，见踩坑 #31），并落一行过站日志对账。
     * 必须在非 EDT 测试线程调用。
     */
    private static void humanGate(String what) throws Exception {
        if (!WIZARD || gateAborted) {
            return;
        }
        gateNo++;
        if (WIZARD_AUTO) {
            System.out.println("GUI-WIZARD auto: 第 " + gateNo + " 站已到，600ms 后自动过站");
        }
        String body = "<html><b>先认一下本站看点，不放心就多待一会儿。</b><br>" + what;
        boolean[] cancelled = new boolean[1];
        boolean[] modalShowing = new boolean[1];
        edt(() -> modalShowing[0] = uiHasModalDialogShowing());
        if (modalShowing[0]) {
            edt(() -> {
                scheduleAutoNext(600);
                WINDOW.toFront();
                cancelled[0] = !Ui.confirmDialog(WINDOW, "GUI 回归向导——人工巡检（第 " + gateNo + " 站）",
                        body, "下一个", false);
            });
        } else {
            CountDownLatch gate = new CountDownLatch(1);
            edt(() -> showGateDialog(body, cancelled, gate));
            gate.await();
        }
        if (cancelled[0] && !gateAborted) {
            gateAborted = true;
            System.out.println("GUI-WIZARD: 人工终止，剩余观察点自动跑完（回归照常全量出结果）");
        }
    }

    /**
     * 给自动过站档找当前弹卡上的"下一个"按钮并合成点击（须在 EDT）；卡未就绪就 500ms 后再来。
     * 只扫标题以"GUI 回归向导"开头的卡——产品搜索条上也有个「下一个」钮（Window.buildSearchBar），
     * 全窗口按文本找会点到它去，向导卡反而没动静（真实踩坑）。
     */
    private static void uiGateNextClick() {
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            if (!(window instanceof javax.swing.JDialog dialog) || !dialog.isShowing()
                    || !dialog.getTitle().startsWith("GUI 回归向导")) {
                continue;
            }
            List<Component> all = new ArrayList<>();
            collectComponents(dialog, all);
            for (Component component : all) {
                if (component instanceof javax.swing.JButton button
                        && "下一个".equals(button.getText()) && button.isEnabled()) {
                    button.doClick();
                    return;
                }
            }
        }
        autoNextRetries++;
        if (autoNextRetries < 20) { // 20 次封顶防真弹不出来的环境死循环
            scheduleAutoNext(500);
        }
    }

    private static int autoNextRetries;

    /** 自动过站档：真卡照弹，delay 后走真实按钮路径合成点击（须在 EDT） */
    private static void scheduleAutoNext(int delay) {
        if (!WIZARD_AUTO) {
            return;
        }
        javax.swing.Timer autoNext = new javax.swing.Timer(delay, e -> uiGateNextClick());
        autoNext.setRepeats(false);
        autoNext.start();
    }

    /** 顶层窗口里有没有正在显示的应用级模态弹窗（工作区弹窗等；非模态说明卡会被其模态作用域拦死） */
    private static boolean uiHasModalDialogShowing() {
        for (java.awt.Window window : java.awt.Window.getWindows()) {
            if (window instanceof java.awt.Dialog dialog && dialog.isShowing()
                    && dialog.getModalityType() != java.awt.Dialog.ModalityType.MODELESS) {
                return true;
            }
        }
        return false;
    }

    /**
     * 非模态说明卡（须在 EDT）：MODELESS + alwaysOnTop 钉主窗右下角，不遮主内容区也不拦
     * 被测窗交互；「下一个」/「终止」都 dispose + countDown（终止先置 abort，测试线程读定夺落日志）。
     */
    private static void showGateDialog(String body, boolean[] cancelled, CountDownLatch gate) {
        javax.swing.JDialog dialog = new javax.swing.JDialog(WINDOW,
                "GUI 回归向导——人工巡检（第 " + gateNo + " 站）", java.awt.Dialog.ModalityType.MODELESS);
        dialog.setAlwaysOnTop(true);
        javax.swing.JLabel messageLabel = new javax.swing.JLabel(body);
        messageLabel.setFont(Ui.FONT_PLAIN);
        messageLabel.setForeground(Ui.TEXT);
        javax.swing.JButton nextButton = Ui.primaryButton("下一个");
        javax.swing.JButton cancelButton = Ui.button("终止向导");
        nextButton.setPreferredSize(new java.awt.Dimension(84, 28));
        cancelButton.setPreferredSize(new java.awt.Dimension(84, 28));
        java.awt.event.ActionListener same = e -> {
            cancelled[0] = e.getSource() == cancelButton;
            if (WIZARD_AUTO && !cancelled[0]) {
                System.out.println("GUI-WIZARD auto: 第 " + gateNo + " 站已自动过站");
            }
            dialog.dispose();
            gate.countDown();
        };
        nextButton.addActionListener(same);
        cancelButton.addActionListener(same);
        javax.swing.JPanel buttons = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancelButton);
        buttons.add(nextButton);
        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout(0, 12));
        content.setBackground(Ui.BG);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 12, 16));
        content.add(messageLabel, java.awt.BorderLayout.CENTER);
        content.add(buttons, java.awt.BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(nextButton);
        // 关闭钮必须无效化：JDialog 默认 X=dispose 但不 countDown，会把 latch 永久挂死
        dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.pack();
        // 钉在主窗右下角内侧：不遮中心内容，也不挡用户点被测窗其它区域
        java.awt.Rectangle windowBounds = WINDOW.getBounds();
        dialog.setLocation(Math.max(windowBounds.x + 12,
                windowBounds.x + windowBounds.width - dialog.getWidth() - 20),
                Math.max(windowBounds.y + 12,
                        windowBounds.y + windowBounds.height - dialog.getHeight() - 20));
        scheduleAutoNext(600);
        dialog.setVisible(true);
    }

    /** 把消息区滚回顶部（走滚动条 setValue，与真实用户拖拽同路径） */
    private static void scrollWaterfallToTop() throws Exception {
        edt(() -> {
            Container viewportParent = WINDOW.getWaterfallPanel().getParent().getParent();
            if (viewportParent instanceof javax.swing.JScrollPane scrollPane) {
                scrollPane.getVerticalScrollBar().setValue(0);
            }
        });
        Thread.sleep(400);
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
    private static void captureDialog(String name, String dialogTitle) throws Exception {
        Path path = SCREENS.resolve(name);
        edt(() -> {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                if (window instanceof javax.swing.JDialog dialog
                        && dialogTitle.equals(dialog.getTitle()) && dialog.isShowing()) {
                    BufferedImage image = new BufferedImage(dialog.getWidth(), dialog.getHeight(),
                            BufferedImage.TYPE_INT_RGB);
                    dialog.printAll(image.createGraphics());
                    try {
                        ImageIO.write(image, "png", new File(path.toString()));
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
    private static boolean uiHasLabelContaining(String fragment) throws Exception {
        boolean[] found = new boolean[1];
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
                    Rectangle buttonBounds = new Rectangle(
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
                    pane.dispatchEvent(new KeyEvent(pane, KeyEvent.KEY_PRESSED,
                            System.currentTimeMillis(), KeyEvent.CTRL_DOWN_MASK,
                            KeyEvent.VK_C, KeyEvent.CHAR_UNDEFINED));
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
                            Rectangle rect = pane.modelToView2D(offset).getBounds();
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
    @SuppressWarnings("unchecked")
    private static List<String[]> sidebarGroupRows() throws Exception {
        List<String[]>[] rows = new List[1];
        rows[0] = new ArrayList<>();
        edt(() -> {
            JList<?> rawList = findConvList(WINDOW);
            if (rawList != null) {
                List<String[]> collected = new ArrayList<>();
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

    /** 侧栏会话列表的行数（分组头+会话行混排，计数看收起/展开后的模型变化够用） */
    private static int sidebarRowCount() throws Exception {
        int[] count = new int[1];
        edt(() -> {
            JList<?> rawList = findConvList(WINDOW);
            count[0] = rawList == null ? -1 : rawList.getModel().getSize();
        });
        return count[0];
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
        int count = component instanceof javax.swing.JTextPane ? 1 : 0;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                count += countTextPanes(child);
            }
        }
        return count;
    }
}

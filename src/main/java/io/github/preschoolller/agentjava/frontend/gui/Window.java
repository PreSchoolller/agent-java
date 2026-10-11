package io.github.preschoolller.agentjava.frontend.gui;

import io.github.preschoolller.agentjava.backend.provider.common.AgentMode;
import io.github.preschoolller.agentjava.backend.provider.common.ReasoningEffortEnum;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import io.github.preschoolller.agentjava.frontend.store.Conversation;
import io.github.preschoolller.agentjava.frontend.store.ConversationStore;
import io.github.preschoolller.agentjava.frontend.store.Message;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.View;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.AWTEvent;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.HeadlessException;
import java.awt.IllegalComponentStateException;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.LayoutManager2;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Agent 应用主窗口。布局：
 * 左侧 Sidebar（可折叠的历史会话面板，左上角汉堡按钮折叠/展开，底部设置入口）；
 * 右侧主区：中部瀑布流（每张白色圆角卡片是一条消息，可滚动）、
 * 输入框（圆角输入区，回车发送，Shift+回车换行）、底部居中的实时数据小胶囊。
 * 层次感靠浅灰主区/侧栏 + 白色卡片体现，不再使用手绘图里的彩色粗边框。
 *
 * 后端接入方式：
 * onInputSubmitted((conversation, text) -> ...) 在用户回车后拿到文本（输入已自动画成卡片），
 * 期间 setGenerating(true)。拿到回复后回到 EDT（SwingUtilities.invokeLater）展示，两种写法：
 * 1) 一次性：addAiMessage(完整回复)（内部已落库）；
 * 2) 流式：AiStream stream = addAiMessage("") 起一张空卡片，逐段 stream.appendChunk(增量)
 *    （内部按 120ms 节流重渲染），结束调 stream.finish() 把最终文本写回会话仓库。
 * AI 回复按 Markdown 渲染（MarkdownRenderer）；重新生成：onRegenerate(conv -> ...) 注册回调，
 * 点击最后一张 AI 卡片上的"重新生成"按钮时旧回复已先从界面移除，后端应调
 * store.removeLastMessage 删掉旧回答再重新请求（回调在 EDT 上执行，耗时操作自己开线程）。
 * setRealtimeInfo 更新底部实时数据框；getStore() 可直接读写会话数据（本地持久化到 ~/.agentjava）。
 */
public class Window extends JFrame {

    /** 瀑布流区域和输入框距离主区左右边缘的留白 */
    private static final int SIDE_MARGIN = 140;
    /** 卡片高度下限，避免一两句话的消息显得太扁 */
    private static final int CARD_MIN_HEIGHT_AI = 96;
    private static final int CARD_MIN_HEIGHT_USER = 84;
    /** 卡片内角色行、时间行、间距和内边距合计占掉的高度，用于由文本高度推算卡片高度 */
    private static final int CARD_FRAME_HEIGHT_AI = 72;
    private static final int CARD_FRAME_HEIGHT_USER = 52;
    /** 用户气泡宽度范围：在面板宽度的 2/3 以内，最窄 320、最宽 560 */
    private static final int USER_CARD_MIN_WIDTH = 320;
    private static final int USER_CARD_MAX_WIDTH = 560;
    /** 卡片左右内边距加描边，用于把瀑布流宽度换算成文本区宽度 */
    private static final int CARD_SIDE_INSETS = 34;
    /** 工具调用卡的头部高度（角色标+工具名+参数+状态一行）与结果区高度帽（超出内部滚动） */
    private static final int TOOL_HEADER_HEIGHT = 26;
    private static final int TOOL_RESULT_CAP = 320;
    /** 展开态结果区下方的复制行（复制参数/复制结果）高度 */
    private static final int TOOL_COPY_ROW_HEIGHT = 28;
    /** 结果文本区的显示字号（Monospaced，与折叠/展开高度测量共用） */
    private static final int TOOL_RESULT_FONT_SIZE = 12;
    /** 折叠工具卡的整卡高度：一行头部 + 上下内衬（不挂时间行） */
    private static final int TOOL_COLLAPSED_HEIGHT = 48;
    /** 展开工具卡的框架高度（内衬 + 时间行 + 内容竖缝），比 AI 卡的 72 小——没有角色标签行 */
    private static final int TOOL_FRAME_EXPANDED = 62;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ConversationStore store;

    /** 主区。以下组件都由 buildUi 创建，换主题时 Window 原地重建（retheme），所以不是 final */
    private JPanel innerPanel;

    /** 瀑布流容器：纵向排列多张消息卡片 */
    private JPanel waterfallPanel;
    private JScrollPane waterfallScroll;
    /** 瀑布流为空时的占位提示，加入第一张卡片后隐藏 */
    private JLabel emptyHint;

    /** 圆角输入区 */
    private Ui.RoundPanel inputBox;
    private JTextPane inputTextArea;
    private JButton sendButton;
    private JButton stopButton;
    /** 输入区右下角：本次对话的模型与思考强度（对话级选择，选项来自当前方案的模型列表） */
    private JComboBox<String> modelCombo;
    private JComboBox<String> effortCombo;
    /** 输入区左下角：权限模式（计划=只读规划 / 编辑=允许改文件 / 完全访问=不受限 / 纯聊天=不挂工具），后端执行循环消费 */
    private JComboBox<String> modeCombo;
    /** 思考强度下拉的选项顺序，与"关闭/低/中/高/超高"标签一一对应 */
    private static final ReasoningEffortEnum[] EFFORT_OPTIONS = {
            ReasoningEffortEnum.NO, ReasoningEffortEnum.LOW, ReasoningEffortEnum.MEDIUM,
            ReasoningEffortEnum.HIGH, ReasoningEffortEnum.ULTRA,
    };
    /** 权限模式下拉的选项顺序，与"计划模式/编辑模式/完全访问/纯聊天"标签一一对应 */
    private static final AgentMode[] MODE_OPTIONS = {
            AgentMode.PLAN, AgentMode.EDIT, AgentMode.FULL_ACCESS, AgentMode.CHAT,
    };

    /** 实时数据小胶囊（底部居中） */
    private Ui.RoundPanel realtimePill;
    private JLabel realtimeLabel;

    /** 已渲染的消息卡片，瀑布流宽度变化时需要按折行结果重算高度 */
    private final List<MessageCard> messageCards = new ArrayList<>();
    /** 瀑布流里已占用的行数（GridBagLayout 的 gridy 游标） */
    private int waterfallRowCount;

    /** 左侧会话栏和主区之间的可拖拽分栏 */
    private JSplitPane splitPane;

    private Sidebar sidebar;
    private Sidebar.Callbacks sidebarCallbacks;
    /** 当前显示在瀑布流里的会话 id，仓库的当前会话变化时据此决定是否重建 */
    private String displayedConvId;
    /** 切换会话后等卡片高度测量完毕，滚到最底部（聊天应用的惯例：打开即最新消息） */
    private boolean pendingScrollToBottom;
    /** 生成中保存了设置：换主题/字号的重建挂起，显示中的会话空闲时补做（rebind 之后的重建对流式引用是安全的） */
    private boolean pendingRebuild;
    /** 托盘图标（系统支持即常驻注册，关闭行为实时读 AppSettings 的偏好）；null=未注册 */
    private TrayIcon trayIcon;
    private BiConsumer<Conversation, String> inputHandler;
    private Runnable stopHandler;
    private Consumer<Conversation> regenerateHandler;
    /** 各会话的生成态：并行 agent 循环下每个会话独立一把锁，输入区/停止按钮永远跟随当前显示的会话 */
    private final Set<String> generatingConversations = new HashSet<>();
    /** 还在进行中的流式句柄（未 finish 的 AiStream/ToolCallHandle）：会话切走卡片被丢弃，切回时按消息同一性重绑 */
    private final List<LiveStream> liveStreams = new ArrayList<>();
    /** 展开结果区的工具卡（P1-4 折叠）：按 callId 记（无 callId 的旧消息退化为时间戳键），
     *  内存态不落盘——重启后回到默认折叠 */
    private final Set<String> expandedToolCalls = new HashSet<>();
    /** 各会话的输入框草稿：切会话保留各自的未发送文本，也防 A 会话的草稿误发进 B。
     *  输入区 DocumentListener 实时写、showConversation 按会话取；内存态不落盘 */
    private final Map<String, String> draftsByConversation = new HashMap<>();

    /** AI 回复的流式句柄：addAiMessage 返回。所有方法须在 EDT 上调用。 */
    public interface AiStream {
        /** 追加一段增量文本；内部按 ~120ms 节流重渲染，无需自己攒批 */
        void appendChunk(String delta);

        /** 整体替换当前内容并立即渲染（流式中途修正用；一次性后端直接 addAiMessage(全文) 即可） */
        void setText(String value);

        /** 结束流式：渲染剩余内容并把最终文本写回会话仓库 */
        void finish();
    }

    /**
     * 进行中的流式句柄共有能力（并行生成，P0-4 之后）：目标会话切走时卡片随瀑布流丢弃重建，
     * 切回时按消息同一性找到新卡片重新挂上（rebind），攒在句柄里的增量一次性补画。
     * finish 时从 liveStreams 摘除。
     */
    private interface LiveStream {
        String conversationId();

        Message message();

        void rebind(MessageCard card);
    }

    /**
     * 一张消息卡片。message 持仓库消息的同一性引用（流式落库会替换成新记录，finish 时同步），
     * 分叉按它定位。卡片骨架（圆角底）常驻瀑布流，内容组件（正文/尾行/悬停菜单）只有卡片位于
     * 视口附近才物化（P2-2 窗口化）；AI 卡片的 markdown 面板恒非空，承载解析与测量数据；
     * 工具调用卡（role=tool）的 markdown 为 null，内容按工具卡建。
     */
    private static final class MessageCard {
        final JPanel card;
        final boolean user;
        final boolean tool;
        final MarkdownPane markdown;
        Message message;
        /** 物化后才存在的组件；drainCard 时置 null */
        JComponent content;
        /** 工具卡头部的参数摘要标签（参数流式上屏时原地改文本）；drainCard 时置 null */
        JLabel toolArgsLabel;
        /**
         * 工具审批（P0-4 权限确认卡）：挂卡片而不是挂按钮——卡片滚出视口会被 drain、换回视口
         * 会重建，按钮随时是新的，等待放行的后端线程只认这个 future。未决（!isDone()）画
         * "待确认 + 允许/拒绝"，已决回到常规运行中态。drainCard 不清它，重建头部要据它画按钮。
         */
        CompletableFuture<Boolean> approval;
        JButton regenerate;
        JButton menu;
        boolean materialized;

        MessageCard(JPanel card, boolean user, boolean tool, Message message, MarkdownPane markdown) {
            this.card = card;
            this.user = user;
            this.tool = tool;
            this.message = message;
            this.markdown = markdown;
        }
    }

    /**
     * 卡片布局：NORTH 角色行（可选）/ CENTER 内容 / SOUTH 尾行，外加一个不占排版空间的右上角"…"浮层。
     * 三段的排布规则照搬 BorderLayout（南段钉底、中段撑满余量）。不直接用 BorderLayout 叠加浮层，
     * 是因为它的约束不是占掉已有区域（无约束 add 顶掉 CENTER）就是抛异常，浮层只能自己管。
     */
    private static final class OverlayCardLayout implements LayoutManager2 {
        private Component north;
        private Component center;
        private Component south;
        private Component overlay;

        @Override
        public void addLayoutComponent(String name, Component comp) {
            switch (name == null ? "" : name) {
                case BorderLayout.NORTH -> north = comp;
                case BorderLayout.CENTER -> center = comp;
                case BorderLayout.SOUTH -> south = comp;
                case "overlay" -> overlay = comp;
                default -> throw new IllegalArgumentException("未知约束: " + name);
            }
        }

        @Override
        public void addLayoutComponent(Component comp, Object constraints) {
            addLayoutComponent((String) constraints, comp);
        }

        @Override
        public void removeLayoutComponent(Component comp) {
            if (comp == north) {
                north = null;
            } else if (comp == center) {
                center = null;
            } else if (comp == south) {
                south = null;
            } else if (comp == overlay) {
                overlay = null;
            }
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int width = 0;
            int height = 0;
            for (Component c : new Component[]{north, center, south}) {
                if (c == null || !c.isVisible()) {
                    continue;
                }
                Dimension d = c.getPreferredSize();
                width = Math.max(width, d.width);
                height += d.height;
            }
            return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int width = parent.getWidth() - insets.left - insets.right;
            int northHeight = sizedHeight(north);
            int southHeight = sizedHeight(south);
            if (north != null && north.isVisible()) {
                north.setBounds(insets.left, insets.top, width, northHeight);
            }
            if (south != null && south.isVisible()) {
                south.setBounds(insets.left, parent.getHeight() - insets.bottom - southHeight, width, southHeight);
            }
            if (center != null && center.isVisible()) {
                int top = insets.top + northHeight;
                int height = Math.max(0, parent.getHeight() - insets.bottom - southHeight - top);
                center.setBounds(insets.left, top, width, height);
            }
            if (overlay != null && overlay.isVisible()) {
                Dimension size = overlay.getPreferredSize();
                overlay.setBounds(parent.getWidth() - size.width - 8, 5, size.width, size.height);
            }
        }

        private int sizedHeight(Component component) {
            return component == null || !component.isVisible() ? 0 : component.getPreferredSize().height;
        }

        @Override
        public Dimension maximumLayoutSize(Container target) {
            return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        @Override
        public float getLayoutAlignmentX(Container target) {
            return 0.5f;
        }

        @Override
        public float getLayoutAlignmentY(Container target) {
            return 0.5f;
        }

        @Override
        public void invalidateLayout(Container target) {
        }
    }

    public Window(String title) throws HeadlessException {
        this(title, new ConversationStore());
    }

    public Window(String title, ConversationStore store) throws HeadlessException {
        super(title);
        this.store = store;
        // 应用图标：与托盘同一份程序化绘制，多尺寸让系统挑最合适的缩放
        setIconImages(java.util.List.of(Ui.createAppIcon(16), Ui.createAppIcon(32), Ui.createAppIcon(48)));

        // 先把保存的主题应用到 Ui 镜像再构建；首次构建与换主题重建走同一条 buildUi 路
        Ui.applyTheme(AppSettings.get().getUiTheme());
        sidebarCallbacks = new Sidebar.Callbacks() {
            @Override
            public void onToggleCollapse() {
                boolean expanded = !sidebar.isExpanded();
                sidebar.setExpanded(expanded);
                splitPane.setDividerLocation(expanded ? Sidebar.EXPANDED_WIDTH : Sidebar.COLLAPSED_WIDTH);
                getContentPane().revalidate();
            }

            @Override
            public void onNewConversation() {
                showConversation(store.newConversation());
            }

            @Override
            public void onSelectConversation(String conversationId) {
                store.switchTo(conversationId); // 变更监听里会触发主区重建
            }

            @Override
            public void onOpenSettings() {
                openSettings();
            }
        };
        store.addChangeListener(this::onStoreChanged);
        buildUi();
        showConversation(store.getActive());
        initCloseBehavior();
        applyPersistedBounds();
    }

    /** 尺寸/位置/分隔条防抖落盘（1 秒无变更才写）：拖动/缩放期间只记内存，停手后落盘 */
    private final Timer boundsSaveTimer = new Timer(1000, e -> AppSettings.get().save());

    {
        boundsSaveTimer.setRepeats(false);
    }

    /** 应用持久化的窗口尺寸/位置与侧栏分隔条：位置不在屏内（拔了显示器等）回落居中 */
    private void applyPersistedBounds() {
        AppSettings settings = AppSettings.get();
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int width = Math.max(720, Math.min(settings.getWindowWidth(), screen.width));
        int height = Math.max(480, Math.min(settings.getWindowHeight(), screen.height));
        setSize(width, height);
        if (settings.getWindowX() >= 0 && settings.getWindowY() >= 0
                && screen.contains(settings.getWindowX() + width / 2, settings.getWindowY() + height / 2)) {
            setLocation(settings.getWindowX(), settings.getWindowY());
        } else {
            setLocation(screen.x + (screen.width - width) / 2, screen.y + (screen.height - height) / 2);
        }
        if (settings.getSidebarDivider() >= 0) {
            splitPane.setDividerLocation(settings.getSidebarDivider());
        }
        // 尺寸/位置/分隔条变更记内存 + 防抖落盘；最大化期间不记，还原后仍是用户习惯的窗口大小
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                rememberBounds();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                rememberBounds();
            }
        });
    }

    private void rememberBounds() {
        if ((getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0) {
            return;
        }
        AppSettings settings = AppSettings.get();
        settings.setWindowWidth(getWidth());
        settings.setWindowHeight(getHeight());
        settings.setWindowX(getX());
        settings.setWindowY(getY());
        boundsSaveTimer.start();
    }

    /**
     * 关闭行为（P2-3）：不用 EXIT_ON_CLOSE——会话与设置都是即改即存，点关闭要么直接退出、
     * 要么（勾了"关闭时最小化到托盘"且系统支持）隐藏窗口挂托盘，生成中的回复在后台继续。
     * Main 不再覆盖默认关闭操作。
     */
    private void initCloseBehavior() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (AppSettings.get().isMinimizeToTray() && trayIcon != null) {
                    setVisible(false);
                } else {
                    exitApp();
                }
            }
        });
        setMinimizeToTray(AppSettings.get().isMinimizeToTray());
    }

    private void exitApp() {
        dispose();
        System.exit(0);
    }

    /** 注册托盘图标（系统支持即注册，常驻；"关闭=隐藏还是退出"由 windowClosing 实时读设置决定） */
    public void setMinimizeToTray(boolean value) {
        boolean supported = !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        if (supported && trayIcon == null) {
            TrayIcon icon = createTrayIcon();
            try {
                SystemTray.getSystemTray().add(icon);
                trayIcon = icon;
            } catch (AWTException e) {
                System.err.println("注册托盘图标失败: " + e.getMessage());
            }
        } else if ((!supported) && trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }

    /** 托盘图标：主题色圆角 + "A"，左键/双击恢复主窗口，右键弹跟随主题的 Swing 菜单。
     * 不用 AWT PopupMenu：原生菜单在部分环境中文渲染成方块，且无法跟随应用主题。 */
    private TrayIcon createTrayIcon() {
        BufferedImage image = Ui.createAppIcon(16); // 与窗口图标同一份绘制
        JPopupMenu popup = new JPopupMenu();
        popup.setLightWeightPopupEnabled(false); // 托盘区在其他窗口之上，必须用重量级弹层
        JMenuItem showItem = Ui.menuItem("显示主窗口");
        showItem.addActionListener(e -> {
            popup.setVisible(false);
            showFromTray();
        });
        JMenuItem exitItem = Ui.menuItem("退出");
        exitItem.addActionListener(e -> exitApp());
        popup.add(showItem);
        popup.add(exitItem);
        // 鼠标离开弹层自动收起（原生托盘菜单的体感）。进出子组件（菜单项）也会触发父容器的
        // exited（踩坑 #16 同源），不能直接藏——按指针屏幕坐标判断真离开了才收
        popup.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                PointerInfo info = MouseInfo.getPointerInfo();
                if (info == null) {
                    popup.setVisible(false);
                    return;
                }
                Point loc = popup.getLocationOnScreen();
                if (!new Rectangle(loc, popup.getSize()).contains(info.getLocation())) {
                    popup.setVisible(false);
                }
            }
        });
        // 弹层用 setVisible(true) 显示、没有所属窗口，Swing 不会在点击别处时替我们收起——
        // 全局 AWT 监听兜底：任何按下发生在弹层外（托盘图标除外，它由 maybeShow 负责开关）就收起
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof MouseEvent me) || me.getID() != MouseEvent.MOUSE_PRESSED
                    || !popup.isVisible()) {
                return;
            }
            if (me.getSource() instanceof TrayIcon) {
                return;
            }
            Point loc = popup.getLocationOnScreen();
            if (!new Rectangle(loc, popup.getSize()).contains(me.getLocationOnScreen())) {
                popup.setVisible(false);
            }
        }, AWTEvent.MOUSE_EVENT_MASK);

        TrayIcon icon = new TrayIcon(image, "Agent");
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> showFromTray()); // Windows 下双击托盘图标
        MouseAdapter popupShower = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                if (popup.isVisible()) {
                    popup.setVisible(false); // 右键再按一次 = 关闭
                } else {
                    popup.setLocation(e.getX(), e.getY()); // TrayIcon 的鼠标事件坐标就是屏幕坐标
                    popup.setVisible(true);
                }
            }
        };
        icon.addMouseListener(popupShower);
        return icon;
    }

    private void showFromTray() {
        setVisible(true);
        setExtendedState(NORMAL);
        toFront();
    }

    /** 按当前主题构建全部界面组件；组件构造期捕获颜色，换主题必须整体重建（见 retheme） */
    private void buildUi() {
        initInputArea();
        initMainArea();
        initRealtimePill();
        initSidebar();

        setLayout(new BorderLayout());
        getContentPane().setBackground(Ui.BG);
        splitPane = Ui.flatSplitPane(sidebar, innerPanel);
        listenSidebarDragging();
        add(splitPane, BorderLayout.CENTER);
    }

    /** 应用设置里的主题并原地重建界面（设置保存后调用）。显示中的会话生成中先挂起，空闲时补做 */
    public void retheme() {
        Ui.applyTheme(AppSettings.get().getUiTheme());
        if (isGenerating(displayedConvId)) {
            pendingRebuild = true;
            return;
        }
        rebuildUi();
    }

    /** 原地重建整个界面换装；只保留侧栏展开状态和分隔条位置，后端回调注册在 Window 上不受影响 */
    private void rebuildUi() {
        boolean expanded = sidebar == null || sidebar.isExpanded();
        int divider = splitPane == null ? Sidebar.EXPANDED_WIDTH : splitPane.getDividerLocation();
        if (sidebar != null) {
            sidebar.detach(); // 旧侧栏注销仓库监听，否则每换一次主题多挂一个
        }
        messageCards.clear();
        getContentPane().removeAll();
        buildUi();
        showConversation(store.getActive());
        if (expanded) {
            splitPane.setDividerLocation(Math.max(divider, Sidebar.EXPANDED_WIDTH));
        } else {
            sidebar.setExpanded(false);
            splitPane.setDividerLocation(Sidebar.COLLAPSED_WIDTH);
        }
        getContentPane().revalidate();
        getContentPane().repaint();
    }

    public ConversationStore getStore() {
        return store;
    }

    public Sidebar getSidebar() {
        return sidebar;
    }

    public JSplitPane getSplitPane() {
        return splitPane;
    }

    /** 瀑布流容器（回归工具 Shot 断言卡片数/物化窗口用） */
    public JPanel getWaterfallPanel() {
        return waterfallPanel;
    }

    /** 往当前对话追加一张用户输入卡片 */
    public void addUserMessage(String text) {
        Message message = store.appendMessage(store.getActive().getId(), Conversation.ROLE_USER, text);
        insertMessageCard(message);
        updateMaterialization(); // 新卡片在末尾，窗口化时补物化
        scrollToBottom();
    }

    /**
     * 往当前对话追加一张 AI 输出卡片（Markdown 渲染），返回流式句柄。
     * 一次性后端直接 addAiMessage(完整回复)；流式后端先 addAiMessage("")，再逐段
     * appendChunk，结束时 finish()（会把最终文本写回会话仓库）。须在 EDT 上调用。
     */
    public AiStream addAiMessage(String text) {
        return addAiMessage(store.getActive().getId(), text);
    }

    /**
     * 往指定会话追加 AI 输出卡片（并行生成时目标会话可能不在前台）：非显示中的会话只落库不出卡，
     * 流式增量攒在句柄里，切回该会话时经 rebind 一次性补画。须在 EDT 上调用。
     */
    public AiStream addAiMessage(String conversationId, String text) {
        Message message = store.appendMessage(conversationId, Conversation.ROLE_AI, text);
        MessageCard card = conversationId.equals(displayedConvId) ? insertMessageCard(message) : null;
        if (card != null) {
            updateMaterialization(); // 流式卡片是最后一张，窗口化时保证它已物化
            scrollToBottom();
        }
        AiStreamImpl stream = new AiStreamImpl(conversationId, message, card);
        liveStreams.add(stream);
        return stream;
    }

    /**
     * agent 工具调用的展示句柄：addToolCall 返回。所有方法须在 EDT 上调用。
     */
    public interface ToolCallHandle {
        /** 参数增量上屏：只更新卡片显示（内部 120ms 节流），不落库；终态参数以 finish 为准 */
        void appendArgs(String delta);

        /** 回填执行结果并结束：卡片翻转到完成/失败态，结果落库 */
        void finish(String result, boolean success, String arguments);

        /**
         * 工具执行前需要用户放行（P0-4 权限确认卡）：卡片头部翻到"待确认"态，
         * 出"允许/拒绝"两按钮；点击把 true/false 写进 decision。卡片只负责这个 future 的写入，
         * 批了要不要执行、怎么执行由等待方（后端线程）决定，卡片不关心。
         * 待确认只是显示态：不落库（崩了重启按"运行中"残留看，同 P0-5 的边界）。
         * 句柄已 finish（停止/异常清理）时直接判拒绝，绝不让调用线程挂在永没人点的卡上。
         * 须在 EDT 上调用。
         */
        void requestApproval(CompletableFuture<Boolean> decision);
    }

    /**
     * 往当前对话追加一张工具调用卡片（"运行中"状态）。agent 循环里每次调工具：
     * 先 addToolCall(工具名, 参数摘要) 出卡，参数逐段生成时 handle.appendArgs(增量) 上屏
     * （只显示不落库），执行完 handle.finish(结果文本, 是否成功, 完整参数) 回填终态。
     * 与 AiStream 对称：后端在 EDT 上调用。须在 EDT 上调用。
     */
    public ToolCallHandle addToolCall(String toolName, String argsSummary, String callId) {
        return addToolCall(store.getActive().getId(), toolName, argsSummary, callId);
    }

    /** 指定会话的版本（并行生成时目标会话可能不在前台）：非显示中的会话只落库，卡片等切回时重绑上屏 */
    public ToolCallHandle addToolCall(String conversationId, String toolName, String argsSummary, String callId) {
        Message message = store.appendToolCall(conversationId, toolName, argsSummary, callId);
        MessageCard card = conversationId.equals(displayedConvId) ? insertMessageCard(message) : null;
        if (card != null) {
            updateMaterialization(); // 新卡片在末尾，窗口化时补物化
            scrollToBottom();
        }
        ToolCallImpl handle = new ToolCallImpl(conversationId, message, card);
        liveStreams.add(handle);
        return handle;
    }

    /** 工具调用卡的落库与刷新：参数增量只显示不写盘（120ms 节流，与 AiStream 同款），finish 时终态参数与结果一起落库 */
    private final class ToolCallImpl implements ToolCallHandle, LiveStream {
        private final String conversationId;
        private final Message message;
        /** 可能为 null（目标会话不在前台）：切回会话时经 rebind 挂上新卡片 */
        private MessageCard card;
        /** 已到达的参数增量：只攒在内存，卡片滚出视口后重新物化时也从这里画 */
        private final StringBuilder args = new StringBuilder();
        private final Timer argsTimer = new Timer(120, e -> flushArgs());
        private boolean finished;
        /** 待确认的审批 future：卡片不在前台时先挂在句柄上，rebind 时挪到卡片 */
        private CompletableFuture<Boolean> pendingApproval;

        /** 有没有还没决出的审批（侧栏"待确认"标注的判断依据） */
        boolean hasPendingApproval() {
            return pendingApproval != null && !pendingApproval.isDone();
        }

        private ToolCallImpl(String conversationId, Message message, MessageCard card) {
            this.conversationId = conversationId;
            this.message = message;
            this.card = card;
            argsTimer.setRepeats(false);
        }

        @Override
        public String conversationId() {
            return conversationId;
        }

        @Override
        public Message message() {
            return message;
        }

        @Override
        public void rebind(MessageCard newCard) {
            this.card = newCard;
            if (pendingApproval != null && !finished) {
                newCard.approval = pendingApproval; // 审批没决完就切走了：待确认态跟到新卡片上
            }
            flushArgs(); // 把切走期间攒的参数一次性画上
        }

        @Override
        public void appendArgs(String delta) {
            if (finished || delta == null || delta.isEmpty()) {
                return;
            }
            args.append(delta);
            if (!argsTimer.isRunning()) {
                argsTimer.start(); // EDT 上的 Swing Timer，到期统一刷一次头部
            }
        }

        /** 把攒下的参数刷到卡片头部；不动卡片高度（头部单行，摘要长度只影响标签宽度） */
        private void flushArgs() {
            if (finished || args.length() == 0 || card == null) {
                return;
            }
            // 同步卡面消息：卡片此刻没物化也没关系，fillCard 重建头部时读的就是这份
            Message m = card.message;
            card.message = new Message(m.role(), m.text(), m.time(), m.toolName(),
                    args.toString(), m.toolStatus(), m.callId());
            if (!conversationId.equals(displayedConvId) || !card.materialized || card.toolArgsLabel == null) {
                return;
            }
            card.toolArgsLabel.setText(toolArgsSummary(args.toString()));
            card.card.revalidate();
            card.card.repaint();
        }

        @Override
        public void finish(String result, boolean success, String arguments) {
            System.out.println("工具渲染结尾：" + System.currentTimeMillis());
            if (finished) {
                return;
            }
            finished = true;
            argsTimer.stop();
            liveStreams.remove(this);
            refreshSidebarBadges(); // 兜底：审批未来得及决出就被清理时熄灭"待确认"
            String finalArgs = (arguments == null || arguments.isEmpty()) ? args.toString() : arguments;
            Message updated = store.completeToolCall(conversationId, message, result, success, finalArgs);
            System.out.println("工具入库结尾：" + System.currentTimeMillis());
            if (updated == null) {
                return;
            }
            if (card == null) {
                return; // 会话一直在后台：没有卡片可刷，仓库记录即终态，切回时按落库内容画
            }
            card.message = updated;
            if (conversationId.equals(displayedConvId) && messageCards.contains(card)) {
                rebuildCard(card);
            }
            System.out.println("工具渲染真结尾：" + System.currentTimeMillis());
        }

        @Override
        public void requestApproval(CompletableFuture<Boolean> decision) {
            if (finished) {
                // 卡片已终结（停止/异常清理抢在前面）：不会再有人点按钮，直接判否，别挂住等待方
                decision.complete(false);
                return;
            }
            pendingApproval = decision;
            if (card != null) {
                card.approval = decision;
            }
            refreshSidebarBadges(); // 侧栏亮"待确认"标注
            if (card != null && conversationId.equals(displayedConvId)) {
                updateMaterialization(); // 待确认卡一般是最后一张，窗口化下保证它已物化
                if (card.materialized) {
                    rebuildCard(card); // 头部要换"待确认"+两按钮，结构变了走重擦重建
                }
            }
        }
    }

    /**
     * 往当前对话追加一张 AI 输出卡片（Markdown 渲染），返回流式句柄。
     * 一次性后端直接 addAiMessage(完整回复)；流式后端先 addAiMessage("")，再逐段
     * appendChunk，结束时 finish()（会把最终文本写回会话仓库）。须在 EDT 上调用。
     */
    public void addNotifyMessage(String text) {
        Message message = store.appendMessage(store.getActive().getId(), Conversation.ROLE_AI, text);
        MessageCard card = insertMessageCard(message);
        updateMaterialization(); // 流式卡片是最后一张，窗口化时保证它已物化
        scrollToBottom();
    }

    /** 设置输入框回车提交后的回调：输入会先自动加为用户卡片并清空输入框，然后 (会话, 文本) 交给回调 */
    public void onInputSubmitted(BiConsumer<Conversation, String> handler) {
        this.inputHandler = handler;
    }

    /** 设置点击"停止"按钮后的回调（setGenerating(true) 时输入区右侧会出现停止按钮） */
    public void onStopRequested(Runnable handler) {
        this.stopHandler = handler;
    }

    /**
     * 设置"重新生成"回调：点击最后一张 AI 卡片上的"重新生成"按钮后触发（EDT 上执行）。
     * 触发时旧回复已从界面移除，后端应先 store.removeLastMessage(conv.getId()) 删掉旧回答，
     * 再重新请求并照常走 addAiMessage / 流式句柄展示新回复。
     */
    public void onRegenerate(Consumer<Conversation> handler) {
        this.regenerateHandler = handler;
    }

    /**
     * 标记某个会话的生成态（并行 agent 循环，每个会话独立）：发送/停止按钮、输入框置灰、
     * 重新生成显隐永远跟随当前显示的会话，切换会话时由 showConversation 重新刷。
     */
    public void setConversationGenerating(String conversationId, boolean value) {
        if (value) {
            generatingConversations.add(conversationId);
        } else {
            generatingConversations.remove(conversationId);
        }
        if (conversationId.equals(displayedConvId)) {
            refreshGeneratingUi();
        }
        refreshSidebarBadges(); // 侧栏"生成中"标注跟随
        if (!value && pendingRebuild && !isGenerating(displayedConvId)) {
            // 显示中的会话空闲了：补做生成期间挂起的换装重建（rebind 保证其余会话的流式引用安全）
            pendingRebuild = false;
            rebuildUi();
        }
    }

    /** 兼容入口：作用于当前显示的会话（Shot 回归工具用；主流程走 setConversationGenerating） */
    public void setGenerating(boolean value) {
        setConversationGenerating(displayedConvId, value);
    }

    private boolean isGenerating(String conversationId) {
        return generatingConversations.contains(conversationId);
    }

    /** 按当前显示会话的生成态刷新输入区：发送/停止互切、输入框与下拉置灰、胶囊文案、重新生成显隐 */
    private void refreshGeneratingUi() {
        boolean value = isGenerating(displayedConvId);
        inputTextArea.setEnabled(!value);
        modeCombo.setEnabled(!value);
        modelCombo.setEnabled(!value);
        effortCombo.setEnabled(!value);
        sendButton.setVisible(!value);
        stopButton.setVisible(value);
        inputBox.revalidate();
        inputBox.repaint();
        setRealtimeInfo(value ? "生成中…" : "就绪");
        refreshRegenerateButtons(); // 生成中隐藏"重新生成"，避免叠着两次请求
    }

    /** 更新底部实时数据胶囊的文本，例如 token 用量、耗时等 */
    public void setRealtimeInfo(String text) {
        realtimeLabel.setText(text);
    }

    /** 用会话内容重建瀑布流（切换会话、设置变更后调用），并把窗口标题带上会话名 */
    public void showConversation(Conversation conversation) {
        displayedConvId = conversation.getId();
        messageCards.clear();
        waterfallPanel.removeAll();
        waterfallRowCount = 0;
        // 草稿按会话取回：上次没发完的文本还在（setText 会触发 DocumentListener 写回同值，无碍）
        inputTextArea.setText(draftsByConversation.getOrDefault(conversation.getId(), ""));
        if (conversation.isEmpty()) {
            GridBagConstraints gbc = rowConstraints(false);
            gbc.fill = GridBagConstraints.NONE;
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.insets = new Insets(12, 0, 12, 0);
            waterfallPanel.add(emptyHint, gbc);
            waterfallRowCount++;
            emptyHint.setVisible(true);
            pendingScrollToBottom = false;
        } else {
            emptyHint.setVisible(false);
            pendingScrollToBottom = true;
        }
        for (Message message : conversation.getMessages()) {
            insertMessageCard(message);
        }
        rebindLiveStreams(conversation.getId()); // 切回还在生成的会话：把进行中的句柄挂回新卡片（要在物化前，待确认态才画得出来）
        updateMaterialization(); // 长会话只物化视口附近；短会话在这里全量补齐
        setTitle("Agent - " + displayTitle(conversation));
        waterfallPanel.revalidate();
        waterfallPanel.repaint();
        refreshGeneratingUi(); // 输入区状态跟随目标会话：切到生成中的会话要置灰并出停止按钮
        inputTextArea.requestFocusInWindow();
    }

    /** 把进行中的流式句柄重新挂到重建后的卡片上（按消息同一性匹配）；只处理当前会话的 */
    private void rebindLiveStreams(String conversationId) {
        for (LiveStream stream : liveStreams) {
            if (!stream.conversationId().equals(conversationId)) {
                continue;
            }
            for (MessageCard card : messageCards) {
                if (card.message == stream.message()) {
                    stream.rebind(card);
                    break;
                }
            }
        }
    }

    private void onStoreChanged() {
        String activeId = store.getActive().getId();
        if (!activeId.equals(displayedConvId)) {
            showConversation(store.getActive());
        }
    }

    /** 把一条消息的卡片骨架插到瀑布流末尾：用户卡片定宽靠右、AI 卡片通栏，返回卡片记录。
     * 内容组件不在这里建（窗口化），由 updateMaterialization 决定哪些卡片 fillCard */
    private MessageCard insertMessageCard(Message message) {
        boolean user = Conversation.ROLE_USER.equals(message.role());
        boolean tool = message.isToolCall();
        MarkdownPane markdown = null;
        if (!user && !tool) {
            markdown = new MarkdownPane(AppSettings.get().getContentFontSize());
            markdown.setContent(message.text()); // 只解析+测量，行组件等物化时才建
        }

        Ui.RoundPanel card = Ui.card(user ? Ui.USER_CARD_BG : Ui.CARD_BG);
        card.setLayout(new OverlayCardLayout());
        card.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
        // 先按下限高度占位，等测量出文本实际高度后再修正；宽度交给 GridBag 的 fill/anchor 控制
        card.setPreferredSize(new Dimension(0, CARD_MIN_HEIGHT_AI));

        waterfallPanel.add(card, rowConstraints(user));
        waterfallRowCount++;
        MessageCard messageCard = new MessageCard(card, user, tool, message, markdown);
        if (markdown != null) {
            // 思考块折叠切换（P1-7）后高度变了：重测所在卡片
            markdown.setOnHeightChange(() -> {
                int index = messageCards.indexOf(messageCard);
                if (index >= 0) {
                    fitCardHeight(index);
                }
            });
        }
        attachHoverMenu(messageCard);
        messageCards.add(messageCard);
        emptyHint.setVisible(false);

        fitCardHeight(messageCards.size() - 1);
        waterfallPanel.revalidate();
        return messageCard;
    }

    /** 把卡片内容（角色行/正文/尾行/悬停菜单）建出来；只有视口附近的卡片才物化（P2-2 窗口化） */
    private void fillCard(MessageCard messageCard) {
        if (messageCard.materialized) {
            return;
        }
        messageCard.materialized = true;
        JPanel card = messageCard.card;
        Message message = messageCard.message;
        boolean user = messageCard.user;
        boolean tool = messageCard.tool;

        JComponent content;
        if (tool) {
            content = buildToolContent(messageCard);
        } else if (user) {
            JTextPane textPane = Ui.displayTextPane();
            textPane.setFont(new Font("微软雅黑", Font.PLAIN, AppSettings.get().getContentFontSize()));
            // 没设过前景色的文本组件默认黑字，深色主题下必须显式给（见踩坑 #19）
            textPane.setForeground(Ui.TEXT);
            textPane.setText(message.text());
            content = textPane;
        } else {
            messageCard.markdown.materialize();
            content = messageCard.markdown;
        }
        messageCard.content = content;

        if (!user && !tool) {
            JLabel roleLabel = new JLabel("AI");
            roleLabel.setFont(new Font("微软雅黑", Font.BOLD, 13));
            roleLabel.setForeground(Ui.ACCENT);
            roleLabel.setHorizontalAlignment(SwingConstants.LEFT);
            card.add(roleLabel, BorderLayout.NORTH);
        }

        JPanel timeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        timeRow.setOpaque(false);
        // 折叠的工具卡不挂时间行：整个卡片就是一行头部（时间展开后可见）
        boolean toolCollapsed = tool && !expandedToolCalls.contains(toolKey(message));
        if (!toolCollapsed) {
            JLabel timeLabel = new JLabel(TIME_FORMAT.format(Instant.ofEpochMilli(message.time())));
            timeLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
            timeLabel.setForeground(Ui.TEXT_SECONDARY);
            timeRow.add(timeLabel);
        }
        if (user) {
            // 用户消息复制（计划外）：与 AI 卡"复制"共用按钮实现，拷用户原文
            timeRow.add(createCopyButton(() -> message.text() == null ? "" : message.text()));
        } else if (!tool) {
            MarkdownPane pane = messageCard.markdown;
            timeRow.add(createCopyButton(pane::getText));
            messageCard.regenerate = createRegenerateButton();
            timeRow.add(messageCard.regenerate);
        }
        card.add(content, BorderLayout.CENTER);
        if (!toolCollapsed) {
            card.add(timeRow, BorderLayout.SOUTH);
        }
        if (!user && !tool) {
            // 分叉挂在 AI 回复上（对齐 Codex/ZCode：以某次对话状态为锚点继续），用户卡片不挂菜单
            JButton menuButton = createMenuButton();
            // 浮层用 BorderLayout 不认识的约束名：布局器不会给它分配区域（无约束的 add 会被当成
            // CENTER 顶掉真正的内容组件），位置完全由 OverlayCardLayout.layoutContainer 负责
            card.add(menuButton, "overlay");
            // z-order 0 才是顶层：add 追加到末尾反而是最底层，按钮与内容重叠区的点击会被
            // 透明的内容组件截走（可见但点不到，见踩坑 #21）
            card.setComponentZOrder(menuButton, 0);
            messageCard.menu = menuButton;
            menuButton.addActionListener(e -> showForkMenu(menuButton, messageCard));
        }
        card.doLayout(); // 浮层坐标在布局器里，先摆好再画
        refreshRegenerateButtons();
    }

    /** 头部一行放不下完整参数：折行压扁、超长截断，只当摘要看 */
    private static String toolArgsSummary(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String flat = raw.replace("\n", " ");
        return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
    }

    /** 工具调用卡内容：一行头部（折叠箭头 + "工具"角色标 + 工具名 + 参数摘要 + 状态徽标），默认收成
     *  一行（P1-4）；点头部展开结果区，展开时结果文本下挂"复制参数/复制结果"。待确认态的
     *  "允许/拒绝"按钮挂在头部，折叠不影响审批 */
    private JComponent buildToolContent(MessageCard messageCard) {
        Message message = messageCard.message;
        boolean expanded = expandedToolCalls.contains(toolKey(message));
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        header.setOpaque(false);
        header.setName("tool-header"); // Shot 回归定位（点头部折叠/展开）
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!expandedToolCalls.remove(toolKey(message))) {
                    expandedToolCalls.add(toolKey(message));
                }
                rebuildCard(messageCard);
            }
        });
        // 折叠箭头自绘（Ui.Chevron）：▸▶ 在微软雅黑里缺字形渲染成方框（踩坑 #5，字符探针实测）
        Ui.Chevron chevron = new Ui.Chevron(expanded);
        chevron.setForeground(Ui.TEXT_SECONDARY);
        header.add(chevron);
        JLabel roleLabel = new JLabel("工具");
        roleLabel.setFont(new Font("微软雅黑", Font.BOLD, 13));
        roleLabel.setForeground(Ui.ACCENT);
        header.add(roleLabel);
        JLabel nameLabel = new JLabel(message.toolName());
        nameLabel.setFont(new Font("Monospaced", Font.BOLD, 13));
        nameLabel.setForeground(Ui.TEXT);
        header.add(nameLabel);
        // 参数标签恒建（哪怕此刻还没有参数）：参数流式到达时原地改文本，不用重建头部
        JLabel argsLabel = new JLabel(toolArgsSummary(message.toolArgs()));
        argsLabel.setFont(new Font("Monospaced", Font.PLAIN, 12));
        argsLabel.setForeground(Ui.TEXT_SECONDARY);
        header.add(argsLabel);
        messageCard.toolArgsLabel = argsLabel;
        boolean running = "running".equals(message.toolStatus());
        boolean error = "error".equals(message.toolStatus());
        boolean interrupted = "interrupted".equals(message.toolStatus()); // 崩溃残留（P0-5），灰态
        // 待确认（P0-4 权限确认卡）：卡片手里攥着一个还没被点的审批 future。
        // 徽标沿用三态色位，警示色比"失败"收敛；旁边挂"允许/拒绝"两按钮
        boolean pending = running && messageCard.approval != null && !messageCard.approval.isDone();
        JLabel statusLabel = new JLabel(pending ? "待确认" : running ? "运行中…" : error ? "失败"
                : interrupted ? "已中断" : "完成");
        statusLabel.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        statusLabel.setForeground(pending ? Ui.WARNING : running ? Ui.ACCENT
                : error ? Ui.DANGER : Ui.TEXT_SECONDARY);
        header.add(statusLabel);
        if (pending) {
            header.add(createApprovalButton(messageCard, "允许", true));
            header.add(createApprovalButton(messageCard, "拒绝", false));
        }

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setOpaque(false);
        content.add(header, BorderLayout.NORTH);
        if (expanded) {
            JTextArea result = Ui.displayTextArea();
            result.setFont(new Font("Monospaced", Font.PLAIN, 12));
            // 没设过前景色的文本组件默认黑字，深色主题下必须显式给（见踩坑 #19）
            result.setForeground(Ui.TEXT);
            result.setText(message.text());
            result.setLineWrap(true);
            result.setWrapStyleWord(false);
            JScrollPane scroll = new JScrollPane(result,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false); // 滚动容器背景两处一起设，深色下边框内衬不露白（踩坑 #19）
            scroll.getViewport().setOpaque(false);
            scroll.setViewportBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            Ui.styleScrollPane(scroll);
            content.add(scroll, BorderLayout.CENTER);

            JPanel copyRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            copyRow.setOpaque(false);
            copyRow.add(createCopyButton("复制参数", () -> message.toolArgs() == null ? "" : message.toolArgs()));
            copyRow.add(createCopyButton("复制结果", () -> message.text() == null ? "" : message.text()));
            content.add(copyRow, BorderLayout.SOUTH);
        }
        return content;
    }

    /** 工具卡展开状态键：callId（会话/重启间按它记忆），无 callId 的旧消息退化为时间戳 */
    private static String toolKey(Message message) {
        return message.callId() != null ? message.callId() : "t" + message.time();
    }

    /** 摘掉卡片内容（滚出视口）：组件全部丢弃，解析与测量数据留在 MarkdownPane 里 */
    private void drainCard(MessageCard messageCard) {
        if (!messageCard.materialized) {
            return;
        }
        messageCard.materialized = false;
        messageCard.card.removeAll();
        if (messageCard.markdown != null) {
            messageCard.markdown.dematerialize();
        }
        messageCard.content = null;
        messageCard.toolArgsLabel = null;
        messageCard.regenerate = null;
        messageCard.menu = null;
    }

    /** 工具卡结构变化（待确认 ↔ 运行中 ↔ 终态）时重擦重建内容并重测高度；用户贴着底部就保持贴底 */
    private void rebuildCard(MessageCard card) {
        if (!messageCards.contains(card)) {
            return;
        }
        boolean follow = isNearBottom();
        drainCard(card);
        fillCard(card);
        fitCardHeight(messageCards.indexOf(card));
        if (follow) {
            scrollToBottom();
        }
    }

    /**
     * 窗口化（P2-2）：短会话全量渲染；超过阈值的长会话只物化视口附近（上下各留余量）的卡片，
     * 最后一张恒物化（流式追加与"重新生成"都挂在它上面）。滚动/重排后调用，按当前视口位置收敛。
     */
    private static final int MATERIALIZE_THRESHOLD = 60;
    private static final int MATERIALIZE_MARGIN = 2;
    /** GridBag 每行卡片下方的间距（rowConstraints 里的 insets.bottom），估算视口区间用 */
    private static final int ROW_GAP = 12;

    private void updateMaterialization() {
        int n = messageCards.size();
        if (n <= MATERIALIZE_THRESHOLD) {
            for (MessageCard card : messageCards) {
                fillCard(card);
            }
            return;
        }
        Rectangle visible = waterfallScroll.getViewport().getViewRect();
        int start = 0;
        int top = 0;
        while (start < n && top + rowBlockHeight(start) <= visible.y) {
            top += rowBlockHeight(start);
            start++;
        }
        int end = start;
        int bottom = top;
        while (end < n && bottom < visible.y + visible.height) {
            bottom += rowBlockHeight(end);
            end++;
        }
        int from = Math.max(0, start - MATERIALIZE_MARGIN);
        int to = Math.min(n - 1, end + MATERIALIZE_MARGIN);
        for (int i = 0; i < n; i++) {
            boolean want = i >= from && i <= to || i == n - 1;
            if (want) {
                fillCard(messageCards.get(i));
            } else {
                drainCard(messageCards.get(i));
            }
        }
    }

    /** 一张卡片在瀑布流里占的高度（卡高 + 行间距），供视口区间估算 */
    private int rowBlockHeight(int index) {
        return messageCards.get(index).card.getPreferredSize().height + ROW_GAP;
    }

    /** 卡片右上角的"…"消息操作按钮：默认隐藏，悬停卡片时出现 */
    private JButton createMenuButton() {
        JButton menu = Ui.button("…");
        menu.setFont(new Font("微软雅黑", Font.BOLD, 12));
        menu.setPreferredSize(new Dimension(28, 20));
        menu.setToolTipText("消息操作");
        menu.setVisible(false);
        return menu;
    }

    /** 悬停显示"…"、移出隐藏。进出子组件（文本区、按钮）也会触发父容器的 exited，用指针位置兜底 */
    private void attachHoverMenu(MessageCard messageCard) {
        JPanel card = messageCard.card;
        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (messageCard.menu != null) {
                    setMenuVisible(messageCard.menu, true);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (messageCard.menu == null || isPointerInside(card)) {
                    return; // 卡片已摘空，或只是滑进了卡片内部的子组件
                }
                setMenuVisible(messageCard.menu, false);
            }
        });
    }

    /** 指针当前是否仍在组件范围内（按屏幕坐标判断） */
    private boolean isPointerInside(Component component) {
        PointerInfo info = MouseInfo.getPointerInfo();
        if (info == null) {
            return false;
        }
        try {
            Point location = component.getLocationOnScreen();
            return new Rectangle(location, component.getSize()).contains(info.getLocation());
        } catch (IllegalComponentStateException e) {
            return false; // 组件已被移除或隐藏，悬停状态自然失效
        }
    }

    private void setMenuVisible(JButton menuButton, boolean visible) {
        boolean show = visible && !isGenerating(displayedConvId);
        if (menuButton.isVisible() == show) {
            return;
        }
        menuButton.setVisible(show);
        if (show) {
            Container card = menuButton.getParent();
            card.doLayout(); // 浮层坐标在布局器里，先摆好再画
            card.repaint();
        }
    }

    /** "…"菜单：AI 卡片提供分叉（单条删除已移除，用户卡片不挂菜单） */
    private void showForkMenu(JButton anchor, MessageCard owner) {
        JPopupMenu popup = new JPopupMenu();
        Ui.stylePopupMenu(popup);
        JMenuItem fork = Ui.menuItem("分叉对话");
        fork.addActionListener(e -> forkConversation(owner));
        popup.add(fork);
        popup.show(anchor, 0, anchor.getHeight() + 2);
    }

    /** 分叉：以这条 AI 回复为分叉点开新会话（含这条回复，从该状态继续），原会话不动；
     *  store.fork 会把新会话设为当前并通知，主区自动切过去 */
    private void forkConversation(MessageCard card) {
        if (isGenerating(displayedConvId)) {
            return;
        }
        store.fork(displayedConvId, card.message);
    }

    /** 瀑布流一行的 GridBag 约束：用户卡片靠右上且不定宽，AI 卡片水平撑满 */
    private GridBagConstraints rowConstraints(boolean user) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = waterfallRowCount;
        gbc.weightx = 1;
        gbc.insets = new Insets(0, 0, 12, 0);
        if (user) {
            gbc.anchor = GridBagConstraints.NORTHEAST;
            gbc.fill = GridBagConstraints.NONE;
        } else {
            gbc.anchor = GridBagConstraints.CENTER;
            gbc.fill = GridBagConstraints.HORIZONTAL;
        }
        return gbc;
    }

    /** AI 卡片右下角的复制按钮，拷贝 Supplier 给的文本（Markdown 原文），点击后短暂提示"已复制" */
    private JButton createCopyButton(Supplier<String> source) {
        return createCopyButton("复制", source);
    }

    /** 复制按钮（工具卡 P1-4 的"复制参数/复制结果"与 AI 卡的"复制"共用），点击后短暂提示"已复制" */
    private JButton createCopyButton(String label, Supplier<String> source) {
        JButton copyButton = Ui.button(label);
        copyButton.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        copyButton.setPreferredSize(new Dimension(56, 22));
        copyButton.setToolTipText("复制到剪贴板");
        copyButton.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(source.get()), null);
            copyButton.setText("已复制");
            Timer reset = new Timer(1200, ev -> copyButton.setText(label));
            reset.setRepeats(false);
            reset.start();
        });
        return copyButton;
    }

    /** 重新生成按钮：只在最后一张 AI 卡片上可见（refreshRegenerateButtons 控制显隐） */
    private JButton createRegenerateButton() {
        JButton button = Ui.button("重新生成");
        button.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        button.setPreferredSize(new Dimension(76, 22));
        button.setToolTipText("删除这条回复并让 AI 重新回答");
        button.addActionListener(e -> requestRegenerate());
        return button;
    }

    /** 权限确认卡（P0-4）的"允许/拒绝"：写入 decision 后重建卡片（待确认态消失，回到运行中/终态）。
     *  等待放行的后端线程读同一个 future，卡片只管写入，不管执行 */
    private JButton createApprovalButton(MessageCard card, String text, boolean approved) {
        JButton button = approved ? Ui.primaryButton(text) : Ui.dangerButton(text);
        button.setFont(new Font("微软雅黑", Font.BOLD, 11));
        button.setPreferredSize(new Dimension(52, 22));
        button.addActionListener(e -> {
            CompletableFuture<Boolean> decision = card.approval;
            if (decision == null || decision.isDone()) {
                return; // 已被另一击/外层清理决掉：按钮理论上随重建消失了，兜底不双写
            }
            decision.complete(approved);
            refreshSidebarBadges(); // 审批决出，"待确认"标注熄灭
            rebuildCard(card);
        });
        return button;
    }

    /** 只有最后一张 AI 卡片显示重新生成（且生成中隐藏），其余 AI 卡片的按钮一律藏掉；悬停菜单同步藏起 */
    private void refreshRegenerateButtons() {
        boolean generating = isGenerating(displayedConvId);
        int last = messageCards.size() - 1;
        for (int i = 0; i < messageCards.size(); i++) {
            MessageCard card = messageCards.get(i);
            if (card.regenerate != null) {
                card.regenerate.setVisible(!generating && i == last);
            }
            if (generating && card.menu != null) {
                card.menu.setVisible(false);
            }
        }
    }

    /** 重新生成：先把最后一张 AI 卡片从界面移除（仓库删除由后端在回调里做），再通知后端重新请求 */
    private void requestRegenerate() {
        if (isGenerating(displayedConvId) || messageCards.isEmpty() || messageCards.get(messageCards.size() - 1).user) {
            return;
        }
        MessageCard last = messageCards.remove(messageCards.size() - 1);
        waterfallPanel.remove(last.card);
        waterfallRowCount = Math.max(0, waterfallRowCount - 1);
        refreshRegenerateButtons();
        waterfallPanel.revalidate();
        if (regenerateHandler != null) {
            regenerateHandler.accept(store.getActive());
        }
    }

    private static String displayTitle(Conversation conversation) {
        String title = conversation.getTitle();
        return title == null || title.isBlank() ? "新对话" : title;
    }

    /** 主区：空态提示 + 纵向瀑布流排列的消息卡片，卡片多时可滚动 */
    private void initMainArea() {
        innerPanel = new JPanel(new BorderLayout(10, 10));
        waterfallPanel = new JPanel();
        waterfallScroll = new JScrollPane(waterfallPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        emptyHint = new JLabel("在下方输入，或从左侧选一段历史对话", SwingConstants.CENTER);
        innerPanel.setBackground(Ui.BG);
        waterfallPanel.setBackground(Ui.BG);
        waterfallPanel.setLayout(new GridBagLayout());

        emptyHint.setFont(new Font("微软雅黑", Font.BOLD, 15));
        emptyHint.setForeground(Ui.TEXT_SECONDARY);
        emptyHint.setAlignmentX(Component.CENTER_ALIGNMENT);

        // 首次布局或窗口拉伸导致宽度变化时，重新按折行结果测一遍各卡片高度
        waterfallPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                fitAllCardHeights();
            }
        });

        waterfallScroll.setBorder(BorderFactory.createEmptyBorder(24, SIDE_MARGIN, 8, SIDE_MARGIN));
        waterfallScroll.setBackground(Ui.BG);
        waterfallScroll.getViewport().setBackground(Ui.BG);
        waterfallScroll.getVerticalScrollBar().setUnitIncrement(16);
        Ui.styleScrollPane(waterfallScroll);
        // 滚动即换窗：长会话只物化视口附近的卡片（P2-2）。
        // 监听视口而不是滚动条：滚动条事件到达时视口位置还没同步过来（慢一拍），视口的
        // change 事件在 viewPosition 更新之后触发，getViewRect 才是准的
        waterfallScroll.getViewport().addChangeListener(e -> updateMaterialization());
        innerPanel.add(waterfallScroll, BorderLayout.CENTER);
    }

    /** 把卡片调整为按当前宽度折行后的实际高度；用户气泡限宽后靠右上 */
    private void fitCardHeight(int index) {
        MessageCard messageCard = messageCards.get(index);
        int panelWidth = waterfallPanel.getWidth();
        if (panelWidth <= 0) {
            return; // 尚未完成首次布局，componentResized 触发后会再测
        }
        int userWidth = Math.max(USER_CARD_MIN_WIDTH,
                Math.min(USER_CARD_MAX_WIDTH, panelWidth * 2 / 3));
        int textWidth = messageCard.user ? userWidth - CARD_SIDE_INSETS : panelWidth - CARD_SIDE_INSETS;
        int height;
        if (messageCard.tool) {
            if (expandedToolCalls.contains(toolKey(messageCard.message))) {
                // 展开：头部 + 结果文本（超长封顶，卡片内滚动）+ 复制行；
                // 框架 = 上下内衬 20 + 时间行 ~18 + 内容竖缝 12 + 余量（比 AI 卡小：没有角色标签行）
                int textHeight = Math.min(measureToolResultHeight(messageCard.message.text(), textWidth)
                        + TOOL_HEADER_HEIGHT, TOOL_RESULT_CAP) + TOOL_COPY_ROW_HEIGHT;
                height = Math.max(CARD_MIN_HEIGHT_AI, textHeight + TOOL_FRAME_EXPANDED);
            } else {
                height = TOOL_COLLAPSED_HEIGHT; // 单行：一行头部，不挂时间行（时间展开后可见）
            }
        } else {
            int textHeight = messageCard.user
                    ? measureTextHeight(messageCard.message.text(), AppSettings.get().getContentFontSize(), textWidth)
                    : messageCard.markdown.preferredHeightAt(textWidth);
            int minHeight = messageCard.user ? CARD_MIN_HEIGHT_USER : CARD_MIN_HEIGHT_AI;
            int frameHeight = messageCard.user ? CARD_FRAME_HEIGHT_USER : CARD_FRAME_HEIGHT_AI;
            height = Math.max(minHeight, textHeight + frameHeight);
        }
        // AI 卡片首选宽保持 0、靠 fill 拉伸；用户卡片把宽度写进首选值供 fill=NONE 使用。
        // 都不能把面板宽度写回首选宽：JViewport 会按首选宽撑大面板，resize 再触发 fit，形成正反馈。
        if (messageCard.user) {
            messageCard.card.setPreferredSize(new Dimension(userWidth, height));
        } else {
            messageCard.card.setPreferredSize(new Dimension(0, height));
        }
        messageCard.card.setMaximumSize(null);
        waterfallPanel.revalidate();
    }

    private void fitAllCardHeights() {
        for (int i = 0; i < messageCards.size(); i++) {
            fitCardHeight(i);
        }
        waterfallPanel.revalidate();
        updateMaterialization(); // 宽度变了，视口里的卡片可能换了一批
        if (pendingScrollToBottom) {
            pendingScrollToBottom = false;
            scrollToBottom(); // 高度都量完了再滚，value/max 才是准的
        }
    }

    /** 按给定宽度折行后量出文本高度（宽度先落 8px 档位，命中 MeasureCache 则免测）。
     * 用一次性克隆的 JTextPane 测量，绝不能用显示中组件的 View：
     * setSize 会污染它的视图状态，首选宽度会变成十几亿像素，把整个瀑布流撑爆。 */
    private int measureTextHeight(String text, int fontSize, int width) {
        int w = MeasureCache.bucketWidth(width);
        int[] cached = MeasureCache.get(text, fontSize, w, false);
        if (cached != null) {
            return cached[0];
        }
        JTextPane measurer = new JTextPane();
        measurer.setFont(new Font("微软雅黑", Font.PLAIN, fontSize));
        measurer.setText(text);
        View view = measurer.getUI().getRootView(measurer);
        view.setSize(w, Integer.MAX_VALUE);
        int height = (int) view.getPreferredSpan(View.VERTICAL);
        MeasureCache.put(text, fontSize, w, false, new int[]{height});
        return height;
    }

    /** 工具卡结果区的高度测量：与显示一致用 Monospaced 12 的 JTextArea 量（按正文字号量会多出一截空白）。
     *  MeasureCache 键里字号传负数当独立键位（与正文字号测量不互踢） */
    private int measureToolResultHeight(String text, int width) {
        int w = MeasureCache.bucketWidth(width);
        int[] cached = MeasureCache.get(text, -TOOL_RESULT_FONT_SIZE, w, false);
        if (cached != null) {
            return cached[0];
        }
        JTextArea measurer = new JTextArea();
        measurer.setFont(new Font("Monospaced", Font.PLAIN, TOOL_RESULT_FONT_SIZE));
        measurer.setText(text);
        measurer.setLineWrap(true);
        measurer.setWrapStyleWord(false);
        View view = measurer.getUI().getRootView(measurer);
        view.setSize(w, Integer.MAX_VALUE);
        int height = (int) view.getPreferredSpan(View.VERTICAL);
        MeasureCache.put(text, -TOOL_RESULT_FONT_SIZE, w, false, new int[]{height});
        return height;
    }

    /** 新卡片加入后把瀑布流滚到底部 */
    private void scrollToBottom() {
        EventQueue.invokeLater(() -> {
            JScrollBar bar = waterfallScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
            // 再刷一次，等 revalidate 引发的二次布局完成
            EventQueue.invokeLater(() -> bar.setValue(bar.getMaximum()));
        });
    }

    /** 用户当前是否在瀑布流底部附近：流式追加只有本来就在底部才自动跟随，不劫持阅读位置 */
    private boolean isNearBottom() {
        JScrollBar bar = waterfallScroll.getVerticalScrollBar();
        return bar.getMaximum() - bar.getVisibleAmount() - bar.getValue() < 60;
    }

    /** 流式句柄实现：增量先攒在缓冲里，Swing Timer 按 120ms 节流重渲染；finish 时落库 */
    private final class AiStreamImpl implements AiStream, LiveStream {
        private final String conversationId;
        private final Message message;
        /** 可能为 null（目标会话不在前台）：切回会话时经 rebind 挂上新卡片 */
        private MessageCard card;
        private final StringBuilder full = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();
        private final Timer flushTimer = new Timer(120, e -> flush());
        private boolean finished;
        /** 思考阶段计时（P1-7"已思考 N 秒"）：当前第几个思考块 / 本块开始时间 / 各块时长（秒） */
        private int thinkBlockIndex = -1;
        private long thinkStartedNanos = -1;
        private final Map<Integer, Double> thinkDurations = new HashMap<>();

        private AiStreamImpl(String conversationId, Message message, MessageCard card) {
            this.conversationId = conversationId;
            this.message = message;
            this.card = card;
            this.full.append(message.text() == null ? "" : message.text());
            flushTimer.setRepeats(false);
        }

        @Override
        public String conversationId() {
            return conversationId;
        }

        @Override
        public Message message() {
            return message;
        }

        @Override
        public void rebind(MessageCard newCard) {
            this.card = newCard;
            pushThinkDurations(); // 后台期间攒下的时长补到新卡片
            flush(); // 把切走期间攒的增量一次性画上
        }

        @Override
        public void appendChunk(String delta) {
            if (finished || delta == null || delta.isEmpty()) {
                return;
            }
            trackThinkMarkers(delta);
            full.append(delta);
            pending.append(delta);
            if (!flushTimer.isRunning()) {
                flushTimer.start(); // EDT 上的 Swing Timer，到期统一渲染这一批
            }
        }

        /** 思考阶段计时：<think>/</think> 标记可能独立成块（Main 逐段发）也可能嵌在文本里
         *  （Shot 样例），逐字符扫增量；多个思考块按序号各记各的 */
        private void trackThinkMarkers(String delta) {
            for (int i = 0; i < delta.length(); i++) {
                if (delta.startsWith("<think>", i)) {
                    thinkBlockIndex++;
                    thinkStartedNanos = System.nanoTime();
                    i += 6;
                } else if (delta.startsWith("</think>", i)) {
                    if (thinkStartedNanos > 0) {
                        thinkDurations.merge(thinkBlockIndex,
                                (System.nanoTime() - thinkStartedNanos) / 1_000_000_000.0, Double::sum);
                        thinkStartedNanos = -1;
                    }
                    i += 7;
                }
            }
        }

        /** 已计得的思考时长推给卡片（pane 存下后重解析的行组件才带得上） */
        private void pushThinkDurations() {
            if (card != null && card.markdown != null && !thinkDurations.isEmpty()) {
                card.markdown.setThinkDurations(thinkDurations);
            }
        }

        @Override
        public void setText(String value) {
            if (finished) {
                return;
            }
            full.setLength(0);
            full.append(value == null ? "" : value);
            pending.setLength(0);
            pending.append(full);
            flush();
        }

        @Override
        public void finish() {
            System.out.println("开始收尾：" + System.currentTimeMillis());
            if (finished) {
                return;
            }
            finished = true;
            flushTimer.stop();
            liveStreams.remove(this);
            flush();
            System.out.println("渲染收尾：" + System.currentTimeMillis());
            Message updated = store.updateMessageText(conversationId, message, full.toString());
            System.out.println("入库收尾：" + System.currentTimeMillis());
            if (updated != null && card != null) {
                card.message = updated; // 仓库记录已换成新对象，卡片同步持有（分叉按同一性定位）
            }
        }

        /** 把缓冲内容渲染到卡片并按新高度重排；会话已切走、卡片不在显示中或还没挂上时静默丢弃 */
        private void flush() {
            if (pending.length() == 0 || card == null) {
                return;
            }
            pending.setLength(0);
            int index = messageCards.indexOf(card);
            if (!conversationId.equals(displayedConvId) || index < 0) {
                return;
            }
            boolean follow = isNearBottom(); // 高度马上要变，先记录用户是否本来就在底部
            pushThinkDurations();
            card.markdown.setContent(full.toString());
            fitCardHeight(index);
            if (follow) {
                scrollToBottom();
            }
        }
    }

    /** 底部：输入区（圆角，聚焦变主题色描边）+ 实时数据胶囊 */
    private void initRealtimePill() {
        realtimePill = Ui.panel(16, Ui.PILL_BG, null);
        realtimeLabel = new JLabel("就绪");
        realtimeLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        realtimeLabel.setForeground(Ui.TEXT_SECONDARY);
        realtimePill.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
        realtimePill.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 14));
        realtimePill.add(realtimeLabel);

        JPanel pillWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        pillWrapper.setBackground(Ui.BG);
        pillWrapper.add(realtimePill);

        JPanel southPanel = new JPanel(new BorderLayout(0, 10));
        southPanel.setBackground(Ui.BG);
        southPanel.setBorder(BorderFactory.createEmptyBorder(10, SIDE_MARGIN, 8, SIDE_MARGIN));
        southPanel.add(inputBox, BorderLayout.CENTER);
        southPanel.add(pillWrapper, BorderLayout.SOUTH);

        innerPanel.add(southPanel, BorderLayout.SOUTH);
    }

    /** 输入框回车：输入自动加为用户卡片并清空输入框，(会话, 文本) 再交给 inputHandler */
    private void submitInput() {
        String text = inputTextArea.getText().strip();
        if (text.isEmpty() || isGenerating(displayedConvId)) {
            return;
        }
        addUserMessage(text);
        inputTextArea.setText("");
        if (inputHandler != null) {
            inputHandler.accept(store.getActive(), text);
        }
    }

    private void initInputArea() {
        inputBox = Ui.panel(20, Ui.CARD_BG, Ui.BORDER_STRONG);
        inputTextArea = new JTextPane();
        sendButton = Ui.primaryButton("发送");
        stopButton = Ui.button("停止");
        inputTextArea.setEditable(true);
        inputTextArea.setOpaque(false);
        inputTextArea.setFont(new Font("微软雅黑", Font.PLAIN, 16));
        // 卡片是深色圆角底，文字与光标必须显式设浅色（见踩坑 #19）
        inputTextArea.setForeground(Ui.TEXT);
        inputTextArea.setCaretColor(Ui.TEXT);
        inputTextArea.setPreferredSize(new Dimension(0, 48));
        inputTextArea.setToolTipText("回车发送，Shift+回车换行");
        inputTextArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() != KeyEvent.VK_ENTER || e.isShiftDown()) {
                    return;
                }
                e.consume();
                submitInput();
            }
        });
        // 草稿按会话暂存：输入内容一变就写当前会话的键，切会话由 showConversation 取回
        inputTextArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                saveDraft();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                saveDraft();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                saveDraft();
            }

            private void saveDraft() {
                if (displayedConvId != null) {
                    draftsByConversation.put(displayedConvId, inputTextArea.getText());
                }
            }
        });
        inputTextArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                inputBox.borderColor = Ui.ACCENT;
                inputBox.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                inputBox.borderColor = Ui.BORDER_STRONG;
                inputBox.repaint();
            }
        });

        stopButton.setVisible(false);
        stopButton.setPreferredSize(new Dimension(70, 34));
        stopButton.setToolTipText("中断本次生成");
        stopButton.addActionListener(e -> {
            setGenerating(false);
            if (stopHandler != null) {
                stopHandler.run();
            }
        });
        sendButton.setPreferredSize(new Dimension(70, 34));
        sendButton.setToolTipText("发送（回车）");
        sendButton.addActionListener(e -> submitInput());
        JPanel buttonHolder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonHolder.setOpaque(false);
        buttonHolder.add(sendButton);
        buttonHolder.add(stopButton);

        // 输入框右下角一排：模型选择 + 思考强度 + 发送/停止
        modelCombo = new JComboBox<>();
        for (String model : AppSettings.get().getModels()) {
            modelCombo.addItem(model);
        }
        modelCombo.setSelectedItem(AppSettings.get().getModel());
        if (modelCombo.getSelectedItem() == null && modelCombo.getItemCount() > 0) {
            modelCombo.setSelectedIndex(0);
        }
        modelCombo.setPreferredSize(new Dimension(180, 26));
        modelCombo.setToolTipText("本次对话使用的模型（可选列表在设置面板里维护）");
        effortCombo = new JComboBox<>(new String[]{"关闭", "低", "中", "高", "超高"});
        effortCombo.setSelectedIndex(3);
        effortCombo.setPreferredSize(new Dimension(90, 26));
        effortCombo.setToolTipText("思考强度");
        Ui.styleComboBox(modelCombo);
        Ui.styleComboBox(effortCombo);

        // 输入框左下角：权限模式（计划=只读 / 编辑=允许改文件 / 完全访问=不受限）
        modeCombo = new JComboBox<>(new String[]{"计划模式", "编辑模式", "完全访问", "纯聊天"});
        modeCombo.setSelectedIndex(1);
        modeCombo.setPreferredSize(new Dimension(132, 26));
        modeCombo.setToolTipText("权限模式：计划=只读规划；编辑=允许改文件；完全访问=不受限；纯聊天=不挂工具");
        Ui.styleComboBox(modeCombo);

        // 一排两端：左下角权限模式，右下角模型 + 思考强度 + 发送/停止
        JPanel controlsRow = new JPanel(new BorderLayout(6, 0));
        controlsRow.setOpaque(false);
        controlsRow.add(modeCombo, BorderLayout.WEST);
        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        rightControls.setOpaque(false);
        rightControls.add(modelCombo);
        rightControls.add(effortCombo);
        rightControls.add(buttonHolder);
        controlsRow.add(rightControls, BorderLayout.EAST);

        inputBox.setLayout(new BorderLayout(6, 0));
        inputBox.setBorder(BorderFactory.createEmptyBorder(10, 14, 8, 10));
        inputBox.add(inputTextArea, BorderLayout.CENTER);
        inputBox.add(controlsRow, BorderLayout.SOUTH);
    }

    /** 输入区当前选中的模型（对话级设置） */
    public String getSelectedModel() {
        return (String) modelCombo.getSelectedItem();
    }

    /** 输入区当前选中的思考强度 */
    public ReasoningEffortEnum getSelectedEffort() {
        return EFFORT_OPTIONS[effortCombo.getSelectedIndex()];
    }

    /** 输入区当前选中的权限模式（左下角） */
    public AgentMode getSelectedMode() {
        return MODE_OPTIONS[modeCombo.getSelectedIndex()];
    }

    private void initSidebar() {
        sidebar = new Sidebar(store, sidebarCallbacks);
        sidebar.setBadgeLookup(this::badgeFor);
    }

    /** 侧栏条目的状态标注：待确认审批 > 生成中（待确认本身就意味着链还开着） */
    private Sidebar.Badge badgeFor(String conversationId) {
        for (LiveStream stream : liveStreams) {
            if (stream instanceof ToolCallImpl toolCall && toolCall.hasPendingApproval()
                    && stream.conversationId().equals(conversationId)) {
                return Sidebar.Badge.APPROVAL;
            }
        }
        return generatingConversations.contains(conversationId) ? Sidebar.Badge.GENERATING : null;
    }

    /** 状态标注可能变了（生成态增删 / 审批挂起或决出 / 句柄终结）：侧栏重刷一次。须在 EDT 上调用 */
    private void refreshSidebarBadges() {
        if (sidebar != null) {
            sidebar.refresh();
        }
    }

    /** 拖动分隔条时联动侧栏内容：拖到贴边自动收起，往右拖开自动展开 */
    private void listenSidebarDragging() {
        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            int location = splitPane.getDividerLocation();
            if (!sidebar.isExpanded() && location >= 100) {
                sidebar.setExpanded(true);
            } else if (sidebar.isExpanded() && location < Sidebar.COLLAPSED_WIDTH + 8) {
                sidebar.setExpanded(false);
            }
            // 分隔条位置持久化（与窗口 bounds 同一套防抖落盘）
            AppSettings.get().setSidebarDivider(location);
            boundsSaveTimer.start();
        });
    }

    private void openSettings() {
        SettingsDialog dialog = new SettingsDialog(this);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            retheme(); // 主题/字号等变化后按新设置整体重建（内部含 showConversation）
        }
    }
}

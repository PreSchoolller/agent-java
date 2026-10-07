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
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.View;
import java.awt.BorderLayout;
import java.awt.AWTEvent;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.HeadlessException;
import java.awt.IllegalComponentStateException;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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
import java.util.List;
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
    /** 输入区左下角：权限模式（计划=只读规划 / 编辑=允许改文件 / 完全访问=不受限），后端执行循环消费 */
    private JComboBox<String> modeCombo;
    /** 思考强度下拉的选项顺序，与"关闭/低/中/高/超高"标签一一对应 */
    private static final ReasoningEffortEnum[] EFFORT_OPTIONS = {
            ReasoningEffortEnum.NO, ReasoningEffortEnum.LOW, ReasoningEffortEnum.MEDIUM,
            ReasoningEffortEnum.HIGH, ReasoningEffortEnum.ULTRA,
    };
    /** 权限模式下拉的选项顺序，与"计划模式/编辑模式/完全访问"标签一一对应 */
    private static final AgentMode[] MODE_OPTIONS = {
            AgentMode.PLAN, AgentMode.EDIT, AgentMode.FULL_ACCESS,
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
    /** 生成中保存了设置：换主题/字号的重建挂起，setGenerating(false) 时补做（流式卡片引用不能断） */
    private boolean pendingRebuild;
    /** 托盘图标（系统支持即常驻注册，关闭行为实时读 AppSettings 的偏好）；null=未注册 */
    private TrayIcon trayIcon;
    private BiConsumer<Conversation, String> inputHandler;
    private Runnable stopHandler;
    private Consumer<Conversation> regenerateHandler;
    private boolean generating;

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
     * 一张消息卡片。message 持仓库消息的同一性引用（流式落库会替换成新记录，finish 时同步），
     * 分叉按它定位。卡片骨架（圆角底）常驻瀑布流，内容组件（正文/尾行/悬停菜单）只有卡片位于
     * 视口附近才物化（P2-2 窗口化）；AI 卡片的 markdown 面板恒非空，承载解析与测量数据。
     */
    private static final class MessageCard {
        final JPanel card;
        final boolean user;
        final MarkdownPane markdown;
        Message message;
        /** 物化后才存在的组件；drainCard 时置 null */
        JComponent content;
        JButton regenerate;
        JButton menu;
        boolean materialized;

        MessageCard(JPanel card, boolean user, Message message, MarkdownPane markdown) {
            this.card = card;
            this.user = user;
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

    /** 应用设置里的主题并原地重建界面（设置保存后调用）。生成中先挂起，setGenerating(false) 补做 */
    public void retheme() {
        Ui.applyTheme(AppSettings.get().getUiTheme());
        if (generating) {
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
        Message message = store.appendMessage(store.getActive().getId(), Conversation.ROLE_AI, text);
        MessageCard card = insertMessageCard(message);
        updateMaterialization(); // 流式卡片是最后一张，窗口化时保证它已物化
        scrollToBottom();
        return new AiStreamImpl(store.getActive().getId(), message, card);
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

    /** 标记是否正在生成：发送/停止按钮互切，输入框置灰，实时数据胶囊显示"生成中…" */
    public void setGenerating(boolean value) {
        generating = value;
        if (!value && pendingRebuild) {
            pendingRebuild = false;
            rebuildUi(); // 生成期间挂起的换装重建，现在界面空闲，补做
            return;
        }
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
        updateMaterialization(); // 长会话只物化视口附近；短会话在这里全量补齐
        setTitle("Agent - " + displayTitle(conversation));
        waterfallPanel.revalidate();
        waterfallPanel.repaint();
        inputTextArea.requestFocusInWindow();
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
        MarkdownPane markdown = null;
        if (!user) {
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
        MessageCard messageCard = new MessageCard(card, user, message, markdown);
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

        JComponent content;
        if (user) {
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

        if (!user) {
            JLabel roleLabel = new JLabel("AI");
            roleLabel.setFont(new Font("微软雅黑", Font.BOLD, 13));
            roleLabel.setForeground(Ui.ACCENT);
            roleLabel.setHorizontalAlignment(SwingConstants.LEFT);
            card.add(roleLabel, BorderLayout.NORTH);
        }

        JPanel timeRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        timeRow.setOpaque(false);
        JLabel timeLabel = new JLabel(TIME_FORMAT.format(Instant.ofEpochMilli(message.time())));
        timeLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        timeLabel.setForeground(Ui.TEXT_SECONDARY);
        timeRow.add(timeLabel);
        if (!user) {
            MarkdownPane pane = messageCard.markdown;
            timeRow.add(createCopyButton(pane::getText));
            messageCard.regenerate = createRegenerateButton();
            timeRow.add(messageCard.regenerate);
        }
        card.add(content, BorderLayout.CENTER);
        card.add(timeRow, BorderLayout.SOUTH);
        if (!user) {
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
        messageCard.regenerate = null;
        messageCard.menu = null;
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
        boolean show = visible && !generating;
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
        if (generating) {
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
        JButton copyButton = Ui.button("复制");
        copyButton.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        copyButton.setPreferredSize(new Dimension(56, 22));
        copyButton.setToolTipText("复制这条回复");
        copyButton.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(source.get()), null);
            copyButton.setText("已复制");
            Timer reset = new Timer(1200, ev -> copyButton.setText("复制"));
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

    /** 只有最后一张 AI 卡片显示重新生成（且生成中隐藏），其余 AI 卡片的按钮一律藏掉；悬停菜单同步藏起 */
    private void refreshRegenerateButtons() {
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
        if (generating || messageCards.isEmpty() || messageCards.get(messageCards.size() - 1).user) {
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
        int textHeight = messageCard.user
                ? measureTextHeight(messageCard.message.text(), AppSettings.get().getContentFontSize(), textWidth)
                : messageCard.markdown.preferredHeightAt(textWidth);
        int minHeight = messageCard.user ? CARD_MIN_HEIGHT_USER : CARD_MIN_HEIGHT_AI;
        int frameHeight = messageCard.user ? CARD_FRAME_HEIGHT_USER : CARD_FRAME_HEIGHT_AI;
        int height = Math.max(minHeight, textHeight + frameHeight);
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
    private final class AiStreamImpl implements AiStream {
        private final String conversationId;
        private final Message message;
        private final MessageCard card;
        private final StringBuilder full = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();
        private final Timer flushTimer = new Timer(120, e -> flush());
        private boolean finished;

        private AiStreamImpl(String conversationId, Message message, MessageCard card) {
            this.conversationId = conversationId;
            this.message = message;
            this.card = card;
            this.full.append(message.text() == null ? "" : message.text());
            flushTimer.setRepeats(false);
        }

        @Override
        public void appendChunk(String delta) {
            if (finished || delta == null || delta.isEmpty()) {
                return;
            }
            full.append(delta);
            pending.append(delta);
            if (!flushTimer.isRunning()) {
                flushTimer.start(); // EDT 上的 Swing Timer，到期统一渲染这一批
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
            if (finished) {
                return;
            }
            finished = true;
            flushTimer.stop();
            flush();
            Message updated = store.updateMessageText(conversationId, message, full.toString());
            if (updated != null) {
                card.message = updated; // 仓库记录已换成新对象，卡片同步持有（分叉按同一性定位）
            }
        }

        /** 把缓冲内容渲染到卡片并按新高度重排；会话已切走或卡片不在显示中时静默丢弃 */
        private void flush() {
            if (pending.length() == 0) {
                return;
            }
            pending.setLength(0);
            int index = messageCards.indexOf(card);
            if (!conversationId.equals(displayedConvId) || index < 0) {
                return;
            }
            boolean follow = isNearBottom(); // 高度马上要变，先记录用户是否本来就在底部
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
        if (text.isEmpty() || generating) {
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
        modeCombo = new JComboBox<>(new String[]{"计划模式", "编辑模式", "完全访问"});
        modeCombo.setSelectedIndex(1);
        modeCombo.setPreferredSize(new Dimension(132, 26));
        modeCombo.setToolTipText("权限模式：计划=只读规划；编辑=允许改文件；完全访问=不受限");
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

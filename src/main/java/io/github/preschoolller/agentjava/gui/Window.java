package io.github.preschoolller.agentjava.gui;

import io.github.preschoolller.agentjava.store.AppSettings;
import io.github.preschoolller.agentjava.store.Conversation;
import io.github.preschoolller.agentjava.store.ConversationStore;
import io.github.preschoolller.agentjava.store.Message;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JScrollBar;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.text.View;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.HeadlessException;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
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

    /** 主区 */
    private final JPanel innerPanel = new JPanel(new BorderLayout(10, 10));

    /** 瀑布流容器：纵向排列多张消息卡片 */
    private final JPanel waterfallPanel = new JPanel();
    private final JScrollPane waterfallScroll = new JScrollPane(waterfallPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    /** 瀑布流为空时的占位提示，加入第一张卡片后隐藏 */
    private final JLabel emptyHint = new JLabel("在下方输入，或从左侧选一段历史对话", SwingConstants.CENTER);

    /** 圆角输入区 */
    private final Ui.RoundPanel inputBox = Ui.panel(20, Ui.CARD_BG, Ui.BORDER_STRONG);
    private final JTextPane inputTextArea = new JTextPane();
    private final JButton sendButton = Ui.primaryButton("发送");
    private final JButton stopButton = Ui.button("停止");

    /** 实时数据小胶囊（底部居中） */
    private final Ui.RoundPanel realtimePill = Ui.panel(16, Ui.PILL_BG, null);
    private final JLabel realtimeLabel = new JLabel("就绪");

    /** 已渲染的消息卡片，瀑布流宽度变化时需要按折行结果重算高度 */
    private final List<MessageCard> messageCards = new ArrayList<>();
    /** 瀑布流里已占用的行数（GridBagLayout 的 gridy 游标） */
    private int waterfallRowCount;

    /** 左侧会话栏和主区之间的可拖拽分栏 */
    private final JSplitPane splitPane;

    private Sidebar sidebar;
    /** 当前显示在瀑布流里的会话 id，仓库的当前会话变化时据此决定是否重建 */
    private String displayedConvId;
    /** 切换会话后等卡片高度测量完毕，滚到最底部（聊天应用的惯例：打开即最新消息） */
    private boolean pendingScrollToBottom;
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

    /** 一张消息卡片：卡片面板、内容区、是否为用户消息；AI 卡片另有 Markdown 内容面板与重新生成按钮 */
    private record MessageCard(JPanel card, JComponent content, boolean user,
                               MarkdownPane markdown, JButton regenerate) {
    }

    public Window(String title) throws HeadlessException {
        this(title, new ConversationStore());
    }

    public Window(String title, ConversationStore store) throws HeadlessException {
        super(title);
        this.store = store;

        initInputArea();
        initMainArea();
        initRealtimePill();
        initSidebar();

        showConversation(store.getActive());
        store.addChangeListener(this::onStoreChanged);

        setLayout(new BorderLayout());
        getContentPane().setBackground(Ui.BG);
        splitPane = Ui.flatSplitPane(sidebar, innerPanel);
        listenSidebarDragging();
        add(splitPane, BorderLayout.CENTER);
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

    /** 往当前对话追加一张用户输入卡片 */
    public void addUserMessage(String text) {
        Message message = store.appendMessage(store.getActive().getId(), Conversation.ROLE_USER, text);
        insertMessageCard(message);
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
        scrollToBottom();
        return new AiStreamImpl(store.getActive().getId(), message, card);
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
        inputTextArea.setEnabled(!value);
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

    /** 把一条消息画成卡片插到瀑布流末尾：用户卡片定宽靠右、AI 卡片通栏（Markdown 渲染），返回卡片记录 */
    private MessageCard insertMessageCard(Message message) {
        boolean user = Conversation.ROLE_USER.equals(message.role());

        JComponent content;
        MarkdownPane markdown = null;
        if (user) {
            JTextPane textPane = new JTextPane();
            textPane.setEditable(false);
            textPane.setOpaque(false);
            textPane.setFont(new Font("微软雅黑", Font.PLAIN, AppSettings.get().getContentFontSize()));
            textPane.setText(message.text());
            content = textPane;
        } else {
            markdown = new MarkdownPane(AppSettings.get().getContentFontSize());
            markdown.setMarkdown(message.text());
            content = markdown;
        }

        Ui.RoundPanel card = Ui.card(user ? Ui.USER_CARD_BG : Ui.CARD_BG);
        card.setLayout(new BorderLayout(0, 6));
        card.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

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
        JButton regenerate = null;
        if (!user) {
            MarkdownPane pane = markdown;
            timeRow.add(createCopyButton(pane::getText));
            regenerate = createRegenerateButton();
            timeRow.add(regenerate);
        }
        card.add(content, BorderLayout.CENTER);
        card.add(timeRow, BorderLayout.SOUTH);

        // 先按下限高度占位，等测量出文本实际高度后再修正；宽度交给 GridBag 的 fill/anchor 控制
        card.setPreferredSize(new Dimension(0, CARD_MIN_HEIGHT_AI));

        waterfallPanel.add(card, rowConstraints(user));
        waterfallRowCount++;
        MessageCard messageCard = new MessageCard(card, content, user, markdown, regenerate);
        messageCards.add(messageCard);
        emptyHint.setVisible(false);

        fitCardHeight(messageCards.size() - 1);
        refreshRegenerateButtons();
        waterfallPanel.revalidate();
        return messageCard;
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

    /** 只有最后一张 AI 卡片显示重新生成（且生成中隐藏），其余 AI 卡片的按钮一律藏掉 */
    private void refreshRegenerateButtons() {
        int last = messageCards.size() - 1;
        for (int i = 0; i < messageCards.size(); i++) {
            JButton button = messageCards.get(i).regenerate();
            if (button != null) {
                button.setVisible(!generating && i == last);
            }
        }
    }

    /** 重新生成：先把最后一张 AI 卡片从界面移除（仓库删除由后端在回调里做），再通知后端重新请求 */
    private void requestRegenerate() {
        if (generating || messageCards.isEmpty() || messageCards.get(messageCards.size() - 1).user()) {
            return;
        }
        MessageCard last = messageCards.remove(messageCards.size() - 1);
        waterfallPanel.remove(last.card());
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
        int textWidth = messageCard.user() ? userWidth - CARD_SIDE_INSETS : panelWidth - CARD_SIDE_INSETS;
        int textHeight = messageCard.user()
                ? measureTextHeight((JTextPane) messageCard.content(), textWidth)
                : messageCard.markdown().preferredHeightAt(textWidth);
        int minHeight = messageCard.user() ? CARD_MIN_HEIGHT_USER : CARD_MIN_HEIGHT_AI;
        int frameHeight = messageCard.user() ? CARD_FRAME_HEIGHT_USER : CARD_FRAME_HEIGHT_AI;
        int height = Math.max(minHeight, textHeight + frameHeight);
        // AI 卡片首选宽保持 0、靠 fill 拉伸；用户卡片把宽度写进首选值供 fill=NONE 使用。
        // 都不能把面板宽度写回首选宽：JViewport 会按首选宽撑大面板，resize 再触发 fit，形成正反馈。
        if (messageCard.user()) {
            messageCard.card().setPreferredSize(new Dimension(userWidth, height));
        } else {
            messageCard.card().setPreferredSize(new Dimension(0, height));
        }
        messageCard.card().setMaximumSize(null);
        waterfallPanel.revalidate();
    }

    private void fitAllCardHeights() {
        for (int i = 0; i < messageCards.size(); i++) {
            fitCardHeight(i);
        }
        waterfallPanel.revalidate();
        if (pendingScrollToBottom) {
            pendingScrollToBottom = false;
            scrollToBottom(); // 高度都量完了再滚，value/max 才是准的
        }
    }

    /** 按给定宽度折行后量出文本高度。
     * 用一次性克隆的 JTextPane 测量，绝不能用显示中组件的 View：
     * setSize 会污染它的视图状态，首选宽度会变成十几亿像素，把整个瀑布流撑爆。 */
    private int measureTextHeight(JTextPane area, int width) {
        JTextPane measurer = new JTextPane();
        measurer.setFont(area.getFont());
        measurer.setText(area.getText());
        View view = measurer.getUI().getRootView(measurer);
        view.setSize(width, Integer.MAX_VALUE);
        return (int) view.getPreferredSpan(View.VERTICAL);
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
            store.updateMessageText(conversationId, message, full.toString());
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
            card.markdown().setMarkdown(full.toString());
            fitCardHeight(index);
            if (follow) {
                scrollToBottom();
            }
        }
    }

    /** 底部：输入区（圆角，聚焦变主题色描边）+ 实时数据胶囊 */
    private void initRealtimePill() {
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
        inputTextArea.setEditable(true);
        inputTextArea.setOpaque(false);
        inputTextArea.setFont(new Font("微软雅黑", Font.PLAIN, 16));
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

        inputBox.setLayout(new BorderLayout(6, 0));
        inputBox.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        inputBox.add(inputTextArea, BorderLayout.CENTER);
        inputBox.add(buttonHolder, BorderLayout.EAST);
    }

    private void initSidebar() {
        sidebar = new Sidebar(store, new Sidebar.Callbacks() {
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
        });
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
            showConversation(store.getActive()); // 字号等变化后按新设置重建卡片
        }
    }
}

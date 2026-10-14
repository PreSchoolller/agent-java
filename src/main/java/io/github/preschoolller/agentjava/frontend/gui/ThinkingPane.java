package io.github.preschoolller.agentjava.frontend.gui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

/**
 * AI 卡片上的独立思考区：与正文同一张卡片，但独立组件、独立数据、独立增量入口
 * （{@code AiStream.appendThinking}）。思考增量直接追加进本面板的文档，正文的 Markdown
 * 解析路径完全不感知思考——前后拼接 think 标签、与正文混杂、思考/正文乱序到达三个问题
 * 一起消掉（正文通道先到也不影响，各自攒各自的缓冲）。
 * 版式与内联思考块（MarkdownRenderer.ThinkRow，旧格式兼容路径）共用同一套常量：
 * 淡紫圆角底 + 可点头部（自绘三角）+ 次要色斜体正文，超长封顶内部滚动。
 * 折叠语义与 P1-7 一致：未闭合展开（看着吐字）、闭合自动收起成"已思考 N 秒"一行，
 * 用户点过头就以用户为准不再随流式翻转。折叠态高度是定值常量，不进测量缓存键
 * （缓存只存展开高度，同 MarkdownPane 的处理）。
 * 与 MarkdownPane 同款窗口化契约：解析/测量数据常驻，滚动容器与文本组件只在物化时建。
 */
final class ThinkingPane extends Ui.RoundPanel {

    /** 首次布局前没有真实宽度时的兜底测量宽（与 MarkdownPane 同款） */
    private static final int FALLBACK_WIDTH = 640;

    private final Font base;
    // ===== 数据（常驻，不随物化丢弃） =====
    private String text = "";
    private boolean closed = true;
    private Double seconds;
    /** 用户点过头就不再随流式开合翻转（null = 没点过，跟默认走） */
    private Boolean userState;
    private Runnable onHeightChange;

    // ===== 物化后才有的组件 =====
    private JComponent header;
    private JLabel titleLabel;
    private JScrollPane scroll;
    private javax.swing.JTextPane pane;
    /** 已写进文档的字符数：增量只补这段尾差 */
    private int appliedLength;
    private boolean materialized;

    ThinkingPane(int fontSize) {
        super(10, Ui.THINK_BG, Ui.THINK_BORDER);
        this.base = new Font("微软雅黑", Font.PLAIN, fontSize);
        setLayout(new BorderLayout());
        setVisible(false); // 没有思考内容前整块隐藏，不占卡片空间
    }

    void setOnHeightChange(Runnable onHeightChange) {
        this.onHeightChange = onHeightChange;
    }

    // ===== 数据入口（EDT） =====

    /** 历史消息整段注入：视为已结束（默认折叠），没有计时只显示"已思考" */
    void setContent(String thinking) {
        if (thinking == null || thinking.isEmpty()) {
            return;
        }
        apply(thinking, true, null);
    }

    /** 数据变化（流式增量 / 闭合 / 时长）：物化中就地把增量追加进文档，同时通知宿主重测高度 */
    void apply(String text, boolean closed, Double seconds) {
        this.text = text == null ? "" : text;
        this.closed = closed;
        this.seconds = seconds;
        setVisible(!this.text.isEmpty());
        if (materialized && !this.text.isEmpty()) {
            syncView();
        }
        fireHeightChange();
    }

    // ===== 折叠状态 =====

    /** 用户点过头就听用户的；否则思考中展开、结束收起（沿用 P1-7 语义） */
    private boolean collapsedNow() {
        return userState != null ? userState : closed;
    }

    private void toggle() {
        userState = !collapsedNow(); // 用户意愿 = 当前状态的翻转；此后不再随流式开合自动翻转
        syncView(); // 重建正文区，appliedLength 归零重灌
        fireHeightChange();
    }

    /** 思考区块本体高度（不含与正文的间距）：0 = 无内容（整块隐藏） */
    int blockHeightAt(int width) {
        if (text.isEmpty()) {
            return 0;
        }
        if (collapsedNow()) {
            return MarkdownRenderer.THINK_COLLAPSED_HEIGHT;
        }
        int inner = MeasureCache.bucketWidth(Math.max(40, width)
                - MarkdownRenderer.THINK_PAD_LEFT - MarkdownRenderer.THINK_PAD_RIGHT);
        // 缓存永远存展开高度：字号取负当独立键位（与正文/工具结果测量互不驱逐）
        int[] cached = MeasureCache.get(text, -base.getSize(), inner, false);
        int textHeight;
        if (cached != null) {
            textHeight = cached[0];
        } else {
            textHeight = MarkdownRenderer.thinkTextHeight(text, base, inner)
                    + MarkdownRenderer.THINK_PAD_TOP + MarkdownRenderer.THINK_PAD_BOTTOM;
            MeasureCache.put(text, -base.getSize(), inner, false, new int[]{textHeight});
        }
        return MarkdownRenderer.THINK_HEADER_HEIGHT + Math.min(textHeight, MarkdownRenderer.THINK_TEXT_HEIGHT_CAP) + 2;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth() > 0 ? getWidth() : FALLBACK_WIDTH;
        return new Dimension(0, blockHeightAt(width)); // 首选宽恒 0（踩坑 #3 红线）
    }

    // ===== 物化（窗口化契约） =====

    void materialize() {
        if (materialized) {
            return;
        }
        materialized = true;
        if (!text.isEmpty()) {
            rebuildView();
        }
    }

    void dematerialize() {
        if (!materialized) {
            return;
        }
        materialized = false;
        removeAll();
        header = null;
        titleLabel = null;
        scroll = null;
        pane = null;
        appliedLength = 0;
    }

    /** 折叠切换或整段替换才整体重建；纯增量走 syncView 的快路径 */
    private void rebuildView() {
        removeAll();
        pane = null;
        scroll = null;
        appliedLength = 0;
        header = buildHeader();
        add(header, BorderLayout.NORTH);
        if (!collapsedNow()) {
            pane = Ui.displayTextPane();
            pane.setFont(base);
            MarkdownRenderer.attachLinkHandler(pane); // 思考内容里的链接可点（P1-6 同款）
            scroll = new JScrollPane(pane,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false); // 滚动容器背景两处一起设，深色下边框内衬不露白（踩坑 #19）
            scroll.getViewport().setOpaque(false);
            scroll.setViewportBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            Ui.styleScrollPane(scroll);
            JPanel padded = new JPanel(new BorderLayout());
            padded.setOpaque(false);
            padded.setBorder(BorderFactory.createEmptyBorder(MarkdownRenderer.THINK_PAD_TOP,
                    MarkdownRenderer.THINK_PAD_LEFT, 0, MarkdownRenderer.THINK_PAD_RIGHT));
            padded.add(scroll, BorderLayout.CENTER);
            add(padded, BorderLayout.CENTER);
            MarkdownRenderer.appendThinkText(pane.getStyledDocument(), text, base);
            appliedLength = text.length();
            followBottom();
        }
        revalidate();
        repaint();
    }

    /** 数据变化后同步显示：状态一致就只追加尾差（不打断划选），状态翻转才整体重建 */
    private void syncView() {
        boolean collapsed = collapsedNow();
        if (pane == null && !collapsed) {
            rebuildView(); // 折叠 → 展开（或首次有内容和整段替换后重建）
            return;
        }
        if (pane != null && collapsed) {
            rebuildView(); // 展开 → 折叠：拆掉正文区
            return;
        }
        if (pane == null) {
            refreshTitle(); // 折叠态没有正文组件可刷，头部文案要跟着刷（"已思考"→"已思考 N 秒"）
            return;
        }
        if (text.length() < appliedLength) {
            rebuildView(); // 变短 = 整段替换（setText 语义），重灌
            return;
        }
        if (text.length() > appliedLength) {
            boolean pinned = isViewportAtBottom(); // 追加前记录：用户往上翻了就不劫持
            MarkdownRenderer.appendThinkText(pane.getStyledDocument(),
                    text.substring(appliedLength), base);
            appliedLength = text.length();
            if (pinned && !closed) {
                followBottom();
            }
        }
        refreshTitle();
    }

    /** 未闭合时正文恒贴底跟随（看着它吐字）；用户手动往上翻后不再劫持 */
    private boolean isViewportAtBottom() {
        if (scroll == null) {
            return true;
        }
        JViewport viewport = scroll.getViewport();
        java.awt.Component view = viewport.getView();
        return view == null
                || viewport.getViewPosition().y + viewport.getExtentSize().height
                >= view.getHeight() - 4;
    }

    private void followBottom() {
        if (scroll == null || pane == null) {
            return;
        }
        // 布局完成后视口尺寸才是新值，排到布局之后再滚
        SwingUtilities.invokeLater(() -> {
            if (scroll != null && pane != null) {
                int bottom = Math.max(0, pane.getHeight() - scroll.getViewport().getExtentSize().height);
                scroll.getViewport().setViewPosition(new Point(0, bottom));
            }
        });
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.setName("thinking-header"); // Shot 回归定位（点独立思考头部）
        titleLabel = new JLabel(titleText());
        titleLabel.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        titleLabel.setForeground(Ui.TEXT_SECONDARY);
        Ui.Chevron chevron = new Ui.Chevron(!collapsedNow()); // 自绘三角：▸▶ 在微软雅黑里缺字形（踩坑 #5）
        chevron.setForeground(Ui.TEXT_SECONDARY);
        header.add(chevron, BorderLayout.WEST);
        header.add(titleLabel, BorderLayout.CENTER);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggle();
            }
        });
        header.setBorder(BorderFactory.createCompoundBorder(
                collapsedNow() ? null
                        : BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.THINK_BORDER),
                BorderFactory.createEmptyBorder(3, 12, 3, 6)));
        return header;
    }

    private String titleText() {
        if (collapsedNow()) {
            return "已思考" + (seconds == null ? "" : " " + MarkdownRenderer.formatThinkSeconds(seconds));
        }
        return closed ? "思考过程" : "思考中…";
    }

    private void refreshTitle() {
        if (titleLabel != null) {
            titleLabel.setText(titleText());
        }
    }

    private void fireHeightChange() {
        if (onHeightChange != null) {
            onHeightChange.run();
        }
    }
}

package io.github.preschoolller.agentjava.frontend.gui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.View;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 零依赖的轻量 Markdown 渲染器（组件计划 P0-1 选了"自写解析"路线：项目不引第三方包）。
 * 支持标题、段落、行内样式（加粗/斜体/行内代码/删除线/链接/裸 URL）、围栏代码块（带语言标注
 * 与复制按钮）、```diff 块（增/删行按行着色）、有序/无序列表（含嵌套与任务勾选）、引用块、
 * 思考块（&lt;think&gt;…&lt;/think&gt;，流式未闭合时吞到文本尾）、水平分隔线；表格暂不渲染（按普通
 * 段落原样显示），链接只做样式不做点击跳转，这两项留待后续按需补。
 *
 * 结构分三层：
 * parse()      —— 文本 → 块模型（Block），纯数据，可单测；
 * buildRows()  —— 块模型 → 渲染行（Row），连续的文本流块合成一个 StyledDocument 文本面板，
 *                 代码块/引用/分隔线各自独立成组件；
 * measureAt()  —— 每行按指定宽度离屏量高度。测量一律用一次性新建的组件（踩坑记录 #2：
 *                 绝不能在显示中的视图上 setSize）。
 */
public final class MarkdownRenderer {

    private MarkdownRenderer() {
    }

    // ===== 块模型 =====

    /** 行内片段：mono 为行内代码，link 非 null 表示链接（含裸 URL） */
    public record Span(String text, boolean bold, boolean italic, boolean mono, boolean strike, String link) {
    }

    public sealed interface Block permits Paragraph, Heading, ListItem, CodeBlock, Quote, Rule, ThinkBlock,
            TableBlock {
    }

    /** 段落：span 里的 "\n" 表示同段内的软换行 */
    public record Paragraph(List<Span> spans) implements Block {
    }

    public record Heading(int level, List<Span> spans) implements Block {
    }

    /** depth 从 0 起，ordered 为 false 时 number 无意义 */
    public record ListItem(int depth, boolean ordered, int number, List<Span> spans) implements Block {
    }

    /** language 为围栏标注的首个词，可能为空串 */
    public record CodeBlock(String language, String code) implements Block {
    }

    /** 引用块：每条是一行解析后的 span 列表 */
    public record Quote(List<List<Span>> lines) implements Block {
    }

    public record Rule() implements Block {
    }

    /** 思考块：<think>…</think> 包住的推理内容，每条是一行 span 列表；流式未闭合时吞到文本尾。
     *  closed 区分闭合与流式中：折叠的默认态据此定——闭合收起、流式中展开（见 MarkdownPane） */
    public record ThinkBlock(List<List<Span>> lines, boolean closed) implements Block {
    }

    /** 表格块（P1-5）：rows[0] 是表头；rows = 行 → 单元格 → 行内 span 列表。
     *  对齐标记（:---:）暂不解析，全部左对齐； ragged 行（列数不齐）缺的格按空单元格渲染 */
    public record TableBlock(List<List<List<Span>>> rows) implements Block {
    }

    // ===== 渲染行 =====

    /** 一行渲染结果：能建显示组件，也能按宽度离屏量高度。同一行的两条路径用同一份常量，保证高度一致 */
    public interface Row {
        Component component(Font base);

        int measureAt(Font base, int width);
    }

    /** 连续的文本流块（段落/标题/列表项）合成一个文本面板行 */
    private record TextRow(List<Block> units) implements Row {
        @Override
        public Component component(Font base) {
            JTextPane pane = Ui.displayTextPane();
            pane.setFont(base);
            fillFlow(pane.getStyledDocument(), units, base);
            return pane;
        }

        @Override
        public int measureAt(Font base, int width) {
            JTextPane measurer = new JTextPane(); // 一次性克隆测量（踩坑记录 #2）
            measurer.setFont(base);
            fillFlow(measurer.getStyledDocument(), units, base);
            View view = measurer.getUI().getRootView(measurer);
            view.setSize(width, Integer.MAX_VALUE);
            return (int) Math.ceil(view.getPreferredSpan(View.VERTICAL));
        }
    }

    private static final int CODE_HEADER_HEIGHT = 27;
    private static final int CODE_TEXT_HEIGHT_CAP = 420;

    /** 代码块行：浅灰圆角底 + 语言标注 + 复制按钮；超长时代码区内部滚动，卡片不至于无限高 */
    private record CodeRow(CodeBlock code) implements Row {
        @Override
        public Component component(Font base) {
            JPanel panel = new Ui.RoundPanel(10, Ui.CODE_BG, Ui.CODE_BORDER);
            panel.setLayout(new BorderLayout());
            panel.add(codeHeader(code.language(), code.code()), BorderLayout.NORTH);

            JTextArea area = codeArea(base);
            JScrollPane scroll = new JScrollPane(area,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setViewportBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            Ui.styleScrollPane(scroll);
            panel.add(scroll, BorderLayout.CENTER);
            return panel;
        }

        @Override
        public int measureAt(Font base, int width) {
            JTextArea measurer = codeArea(base); // 克隆测量（踩坑记录 #2）
            View view = measurer.getUI().getRootView(measurer);
            view.setSize(Math.max(40, width - 24), Integer.MAX_VALUE);
            int textHeight = (int) Math.ceil(view.getPreferredSpan(View.VERTICAL)) + 16; // 补上 area 的上下内边距
            return CODE_HEADER_HEIGHT + Math.min(textHeight, CODE_TEXT_HEIGHT_CAP) + 2;
        }

        private JTextArea codeArea(Font base) {
            JTextArea area = Ui.displayTextArea();
            area.setText(code.code());
            area.setLineWrap(true);
            area.setWrapStyleWord(false);
            area.setFont(new Font("Monospaced", Font.PLAIN, Math.max(11, base.getSize() - 1)));
            area.setForeground(Ui.TEXT);
            area.setBorder(BorderFactory.createEmptyBorder(6, 12, 10, 12));
            return area;
        }
    }

    /** 代码块/diff 块共用头部：左侧语言标注 + 右侧复制按钮（复制原始文本，点击后短暂提示"已复制"） */
    private static JPanel codeHeader(String language, String raw) {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.CODE_BORDER),
                BorderFactory.createEmptyBorder(3, 10, 3, 6)));
        if (!language.isEmpty()) {
            JLabel lang = new JLabel(language);
            lang.setFont(new Font("Monospaced", Font.PLAIN, 11));
            lang.setForeground(Ui.TEXT_SECONDARY);
            header.add(lang, BorderLayout.WEST);
        }
        JButton copy = Ui.button("复制");
        copy.setFont(new Font("微软雅黑", Font.PLAIN, 11));
        copy.setPreferredSize(new Dimension(48, 20));
        copy.setToolTipText("复制代码");
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(raw), null);
            copy.setText("已复制");
            Timer reset = new Timer(1200, ev -> copy.setText("复制"));
            reset.setRepeats(false);
            reset.start();
        });
        header.add(copy, BorderLayout.EAST);
        return header;
    }

    /** diff 块行（```diff 围栏）：增/删行按行着色，@@ 行头与 ---/+++ 文件行走次要色；
     *  结构复用代码块的圆角底、头部与超长内部滚动 */
    private record DiffRow(CodeBlock code) implements Row {
        @Override
        public Component component(Font base) {
            JPanel panel = new Ui.RoundPanel(10, Ui.CODE_BG, Ui.CODE_BORDER);
            panel.setLayout(new BorderLayout());
            panel.add(codeHeader(code.language(), code.code()), BorderLayout.NORTH);

            JTextPane pane = Ui.displayTextPane();
            pane.setFont(base);
            fillDiff(pane.getStyledDocument(), code.code(), base);
            pane.setBorder(BorderFactory.createEmptyBorder(6, 12, 10, 12));
            JScrollPane scroll = new JScrollPane(pane,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false); // 滚动容器背景两处一起设，深色下边框内衬不露白（踩坑 #19）
            scroll.getViewport().setOpaque(false);
            scroll.setViewportBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            Ui.styleScrollPane(scroll);
            panel.add(scroll, BorderLayout.CENTER);
            return panel;
        }

        @Override
        public int measureAt(Font base, int width) {
            JTextPane measurer = new JTextPane(); // 克隆测量（踩坑记录 #2）
            measurer.setFont(base);
            fillDiff(measurer.getStyledDocument(), code.code(), base);
            View view = measurer.getUI().getRootView(measurer);
            view.setSize(Math.max(40, width - 24), Integer.MAX_VALUE);
            int textHeight = (int) Math.ceil(view.getPreferredSpan(View.VERTICAL)) + 16; // 补上 area 的上下内边距
            return CODE_HEADER_HEIGHT + Math.min(textHeight, CODE_TEXT_HEIGHT_CAP) + 2;
        }
    }

    /**
     * diff 内容逐行写入（显示与测量共用，保证高度一致）：+/− 行着增删色并衬行底，
     * @@ 行头走主题色、---/+++ 文件行走次要色，其余上下文行用正文色。
     */
    private static void fillDiff(StyledDocument doc, String code, Font base) throws IllegalStateException {
        Font mono = new Font("Monospaced", Font.PLAIN, Math.max(11, base.getSize() - 1));
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                try {
                    doc.insertString(doc.getLength(), "\n", null);
                } catch (BadLocationException e) {
                    throw new IllegalStateException(e);
                }
            }
            String line = lines[i];
            Color fg = Ui.TEXT;
            Color bg = null;
            if (line.startsWith("+++") || line.startsWith("---")) {
                fg = Ui.TEXT_SECONDARY;
            } else if (line.startsWith("@@")) {
                fg = Ui.ACCENT;
            } else if (line.startsWith("+")) {
                fg = Ui.DIFF_ADD_FG;
                bg = Ui.DIFF_ADD_BG;
            } else if (line.startsWith("-")) {
                fg = Ui.DIFF_DEL_FG;
                bg = Ui.DIFF_DEL_BG;
            }
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setFontFamily(attrs, mono.getFamily());
            StyleConstants.setFontSize(attrs, mono.getSize());
            StyleConstants.setForeground(attrs, fg);
            if (bg != null) {
                StyleConstants.setBackground(attrs, bg);
            }
            try {
                doc.insertString(doc.getLength(), line, attrs);
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final int QUOTE_PAD_TOP = 8;
    private static final int QUOTE_PAD_LEFT = 12;
    private static final int QUOTE_PAD_RIGHT = 12;
    private static final int QUOTE_PAD_BOTTOM = 8;
    private static final int QUOTE_BAR_WIDTH = 3;
    /** 引用块行：浅灰圆角底 + 左侧竖条 + 次要色文字 */
    private record QuoteRow(Quote quote) implements Row {
        @Override
        public Component component(Font base) {
            JPanel panel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(Ui.QUOTE_BG);
                    g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                    g2.setColor(Ui.QUOTE_BAR);
                    g2.fillRoundRect(0, 3, QUOTE_BAR_WIDTH, getHeight() - 7, 3, 3);
                    g2.dispose();
                }
            };
            panel.setOpaque(false);
            panel.setLayout(new BorderLayout());
            JTextPane pane = Ui.displayTextPane();
            pane.setFont(base);
            fillQuote(pane.getStyledDocument(), quote, base);
            pane.setBorder(BorderFactory.createEmptyBorder(
                    QUOTE_PAD_TOP, QUOTE_PAD_LEFT + QUOTE_BAR_WIDTH, QUOTE_PAD_BOTTOM, QUOTE_PAD_RIGHT));
            panel.add(pane, BorderLayout.CENTER);
            return panel;
        }

        @Override
        public int measureAt(Font base, int width) {
            JTextPane measurer = new JTextPane(); // 克隆测量（踩坑记录 #2）
            measurer.setFont(base);
            fillQuote(measurer.getStyledDocument(), quote, base);
            View view = measurer.getUI().getRootView(measurer);
            view.setSize(Math.max(40, width - QUOTE_PAD_LEFT - QUOTE_PAD_RIGHT - QUOTE_BAR_WIDTH), Integer.MAX_VALUE);
            return (int) Math.ceil(view.getPreferredSpan(View.VERTICAL)) + QUOTE_PAD_TOP + QUOTE_PAD_BOTTOM + 1;
        }
    }

    private static final int TABLE_CELL_PAD_X = 10;
    private static final int TABLE_CELL_PAD_Y = 5;
    private static final int TABLE_HEIGHT_CAP = 420; // 与代码块同款高度帽

    /** 表格行（P1-5）：列宽按单元格内容单行测量取列最大，整表超宽横向内部滚动、行多竖向滚动
     *  （代码块的高度帽套路）；表头加粗 + 衬底，网格线自绘走边框色。单元格用 StyledDocument
     *  复用行内样式解析。测量的列宽/行高与显示用同一套算法，保证高度一致 */
    private record TableRow(TableBlock table) implements Row {
        @Override
        public Component component(Font base) {
            final int[] columnWidths = columnWidths(base);
            final int[] rowHeights = rowHeights(base, columnWidths);
            final int totalWidth;
            final int totalHeight;
            int w = 0;
            for (int width : columnWidths) {
                w += width;
            }
            totalWidth = w;
            int h = 0;
            for (int rh : rowHeights) {
                h += rh;
            }
            totalHeight = h;

            JPanel grid = new JPanel(null) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(Ui.CODE_BG);
                    g2.fillRect(0, 0, totalWidth, rowHeights[0]); // 表头衬底
                    g2.setColor(Ui.BORDER);
                    g2.drawRect(0, 0, totalWidth - 1, totalHeight - 1);
                    int y = 0;
                    for (int r = 0; r < rowHeights.length - 1; r++) {
                        y += rowHeights[r];
                        g2.drawLine(0, y, totalWidth - 1, y);
                    }
                    int x = 0;
                    for (int c = 0; c < columnWidths.length - 1; c++) {
                        x += columnWidths[c];
                        g2.drawLine(x, 0, x, totalHeight - 1);
                    }
                    g2.dispose();
                }
            };
            grid.setOpaque(false);
            int y = 0;
            List<List<List<Span>>> rows = table.rows();
            for (int r = 0; r < rows.size(); r++) {
                int x = 0;
                List<List<Span>> row = rows.get(r);
                for (int c = 0; c < columnWidths.length; c++) {
                    JTextPane cell = Ui.displayTextPane();
                    cell.setFont(base);
                    try {
                        insertSpans(cell.getStyledDocument(), c < row.size() ? row.get(c) : List.of(),
                                base, Ui.TEXT, r == 0, false);
                    } catch (BadLocationException e) {
                        throw new IllegalStateException(e);
                    }
                    cell.setBorder(BorderFactory.createEmptyBorder(
                            TABLE_CELL_PAD_Y, TABLE_CELL_PAD_X, TABLE_CELL_PAD_Y, TABLE_CELL_PAD_X));
                    cell.setBounds(x, y, columnWidths[c], rowHeights[r]);
                    grid.add(cell);
                    x += columnWidths[c];
                }
                y += rowHeights[r];
            }
            grid.setPreferredSize(new Dimension(totalWidth, totalHeight));

            JScrollPane scroll = new JScrollPane(grid,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false); // 表格比视口窄时视口会拉大 grid，滚动容器自身必须透明（踩坑 #19 同源）
            scroll.getViewport().setOpaque(false);
            scroll.setViewportBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.getHorizontalScrollBar().setUnitIncrement(16);
            Ui.styleScrollPane(scroll);
            return scroll;
        }

        @Override
        public int measureAt(Font base, int width) {
            int[] rowHeights = rowHeights(base, columnWidths(base));
            int total = 0;
            for (int rh : rowHeights) {
                total += rh;
            }
            return Math.min(total, TABLE_HEIGHT_CAP) + 1;
        }

        /** 列宽 = 该列所有单元格的自然宽度（单行不折行）取最大 + 左右内衬 */
        private int[] columnWidths(Font base) {
            int columnCount = 0;
            for (List<List<Span>> row : table.rows()) {
                columnCount = Math.max(columnCount, row.size());
            }
            int[] widths = new int[columnCount];
            for (List<List<Span>> row : table.rows()) {
                for (int c = 0; c < columnCount; c++) {
                    List<Span> spans = c < row.size() ? row.get(c) : List.of();
                    widths[c] = Math.max(widths[c], spanWidth(spans, base) + TABLE_CELL_PAD_X * 2);
                }
            }
            return widths;
        }

        /** 行高 = 行内最高的单元格（按列宽折行测）+ 上下内衬 */
        private int[] rowHeights(Font base, int[] columnWidths) {
            int[] heights = new int[table.rows().size()];
            for (int r = 0; r < table.rows().size(); r++) {
                List<List<Span>> row = table.rows().get(r);
                int h = 0;
                for (int c = 0; c < columnWidths.length; c++) {
                    List<Span> spans = c < row.size() ? row.get(c) : List.of();
                    h = Math.max(h, spanHeight(spans, base, columnWidths[c] - TABLE_CELL_PAD_X * 2));
                }
                heights[r] = h + TABLE_CELL_PAD_Y * 2;
            }
            return heights;
        }

        /** 单元格自然宽度：克隆测量（踩坑记录 #2），单行不折行。+1 补亚像素取整误差，防末字被裁 */
        private int spanWidth(List<Span> spans, Font base) {
            JTextPane measurer = new JTextPane();
            measurer.setFont(base);
            try {
                insertSpans(measurer.getStyledDocument(), spans, base, Ui.TEXT, false, false);
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
            View view = measurer.getUI().getRootView(measurer);
            view.setSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
            return (int) Math.ceil(view.getPreferredSpan(View.HORIZONTAL)) + 1;
        }

        /** 单元格高度：按列宽（扣除内衬）折行测 */
        private int spanHeight(List<Span> spans, Font base, int width) {
            JTextPane measurer = new JTextPane();
            measurer.setFont(base);
            try {
                insertSpans(measurer.getStyledDocument(), spans, base, Ui.TEXT, false, false);
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
            View view = measurer.getUI().getRootView(measurer);
            view.setSize(Math.max(20, width), Integer.MAX_VALUE);
            return (int) Math.ceil(view.getPreferredSpan(View.VERTICAL));
        }
    }

    private static final int THINK_HEADER_HEIGHT = 24;    private static final int THINK_PAD_TOP = 6;
    private static final int THINK_PAD_LEFT = 12;
    private static final int THINK_PAD_RIGHT = 12;
    private static final int THINK_PAD_BOTTOM = 8;
    private static final int THINK_TEXT_HEIGHT_CAP = 260;
    /** 折叠态思考行的定值高度（与展开测量的收尾公式对齐）。
     *  折叠不进 MeasureCache：缓存永远存展开高度，折叠差值由 MarkdownPane 按行修正——
     *  键加折叠维度会让同一文本的两份状态互踢（P1-7 计划里预警的坑） */
    static final int THINK_COLLAPSED_HEIGHT = THINK_HEADER_HEIGHT + 2;

    /** 思考块行：淡紫圆角底 + "思考过程"标注 + 次要色斜体文字；超长内部滚动，卡片不至于无限高。
     *  折叠态（P1-7）只留一行"▸ 已思考 N 秒"，点击头部切换；默认态由 MarkdownPane 按闭合/流式定 */
    record ThinkRow(ThinkBlock think) implements Row {
        @Override
        public Component component(Font base) {
            return component(base, false, null, null);
        }

        /** collapsed 折叠态；seconds 为流式计得的思考时长（null 显示"已思考"——整段落库的历史消息没有计时）；
         *  onToggle 头部点击回调（null 不挂点击） */
        public Component component(Font base, boolean collapsed, Double seconds, Runnable onToggle) {
            JPanel panel = new Ui.RoundPanel(10, Ui.THINK_BG, Ui.THINK_BORDER);
            panel.setLayout(new BorderLayout());

            JPanel header = new JPanel(new BorderLayout(8, 0));
            header.setOpaque(false);
            header.setName("think-header"); // Shot 回归定位（点头部折叠/展开）
            String title = collapsed
                    ? "已思考" + (seconds == null ? "" : " " + formatThinkSeconds(seconds))
                    : "思考过程";
            JLabel label = new JLabel(title);
            label.setFont(new Font("微软雅黑", Font.PLAIN, 11));
            label.setForeground(Ui.TEXT_SECONDARY);
            Ui.Chevron chevron = new Ui.Chevron(!collapsed); // 自绘三角：▸▶ 在微软雅黑里缺字形（踩坑 #5）
            chevron.setForeground(Ui.TEXT_SECONDARY);
            header.add(chevron, BorderLayout.WEST);
            header.add(label, BorderLayout.CENTER);
            if (collapsed) {
                header.setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 6));
                panel.add(header, BorderLayout.CENTER);
            } else {
                header.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.THINK_BORDER),
                        BorderFactory.createEmptyBorder(3, 12, 3, 6)));
                panel.add(header, BorderLayout.NORTH);

                JTextPane pane = Ui.displayTextPane();
                pane.setFont(base);
                fillThink(pane.getStyledDocument(), think, base);
                JScrollPane scroll = new JScrollPane(pane,
                        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                scroll.setBorder(BorderFactory.createEmptyBorder());
                scroll.setOpaque(false); // 滚动容器背景两处一起设，深色下边框内衬不露白（踩坑 #19）
                scroll.getViewport().setOpaque(false);
                scroll.setViewportBorder(BorderFactory.createEmptyBorder());
                scroll.getVerticalScrollBar().setUnitIncrement(16);
                Ui.styleScrollPane(scroll);
                panel.add(scroll, BorderLayout.CENTER);
            }
            if (onToggle != null) {
                header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                header.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        onToggle.run();
                    }
                });
            }
            return panel;
        }

        @Override
        public int measureAt(Font base, int width) {
            JTextPane measurer = new JTextPane(); // 克隆测量（踩坑记录 #2）
            measurer.setFont(base);
            fillThink(measurer.getStyledDocument(), think, base);
            View view = measurer.getUI().getRootView(measurer);
            view.setSize(Math.max(40, width - THINK_PAD_LEFT - THINK_PAD_RIGHT), Integer.MAX_VALUE);
            int textHeight = (int) Math.ceil(view.getPreferredSpan(View.VERTICAL))
                    + THINK_PAD_TOP + THINK_PAD_BOTTOM;
            return THINK_HEADER_HEIGHT + Math.min(textHeight, THINK_TEXT_HEIGHT_CAP) + 2;
        }
    }

    private static String formatThinkSeconds(double seconds) {
        if (seconds >= 10) {
            return Math.round(seconds) + " 秒";
        }
        return String.format(Locale.ROOT, "%.1f 秒", seconds);
    }

    private static final int RULE_HEIGHT = 13;
    /** 水平分隔线行 */
    private record RuleRow() implements Row {
        @Override
        public Component component(Font base) {
            JPanel line = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(Ui.BORDER);
                    g.fillRect(0, getHeight() / 2, getWidth(), 1);
                }
            };
            line.setOpaque(false);
            return line;
        }

        @Override
        public int measureAt(Font base, int width) {
            return RULE_HEIGHT;
        }
    }

    /** 把块模型分组渲染：连续的文本流块合进一个 TextRow，其余块各自一行 */
    public static List<Row> buildRows(List<Block> blocks, Font base) {
        List<Row> rows = new ArrayList<>();
        List<Block> flow = new ArrayList<>();
        for (Block block : blocks) {
            if (block instanceof Paragraph || block instanceof Heading || block instanceof ListItem) {
                flow.add(block);
                continue;
            }
            if (!flow.isEmpty()) {
                rows.add(new TextRow(flow));
                flow = new ArrayList<>();
            }
            if (block instanceof CodeBlock code) {
                // ```diff 围栏走按行着色的 DiffRow，其余语言仍是普通代码块
                rows.add("diff".equals(code.language()) ? new DiffRow(code) : new CodeRow(code));
            } else if (block instanceof Quote quote) {
                rows.add(new QuoteRow(quote));
            } else if (block instanceof ThinkBlock think) {
                rows.add(new ThinkRow(think));
            } else if (block instanceof TableBlock table) {
                rows.add(new TableRow(table));
            } else if (block instanceof Rule) {
                rows.add(new RuleRow());
            }
        }
        if (!flow.isEmpty()) {
            rows.add(new TextRow(flow));
        }
        return rows;
    }

    // ===== 文本面板内容构建（显示与测量共用，保证高度一致） =====

    private static void fillFlow(StyledDocument doc, List<Block> units, Font base) {
        boolean first = true;
        for (Block unit : units) {
            if (!first) {
                try {
                    // StyledDocument 用 \n 分段：块之间不补 \n 会全部挤进同一个段落
                    doc.insertString(doc.getLength(), "\n", null);
                } catch (BadLocationException e) {
                    throw new IllegalStateException(e);
                }
            }
            SimpleAttributeSet para = new SimpleAttributeSet();
            Font font = base;
            String prefix = "";
            if (unit instanceof Heading heading) {
                int bump = heading.level() <= 1 ? 6 : heading.level() == 2 ? 5 : heading.level() == 3 ? 3
                        : heading.level() == 4 ? 2 : 1;
                font = base.deriveFont((float) (base.getSize() + bump));
                StyleConstants.setSpaceAbove(para, first ? 0 : 10);
                StyleConstants.setSpaceBelow(para, 4);
            } else if (unit instanceof ListItem item) {
                // 悬挂缩进：首行从 marker 起排，折行后与内容对齐（宽度按 marker 估的，够用）
                int hang = item.ordered() ? (item.number() >= 10 ? 26 : 17) : 14;
                StyleConstants.setLeftIndent(para, 10 + item.depth() * 16 + hang);
                StyleConstants.setFirstLineIndent(para, -hang);
                StyleConstants.setSpaceAbove(para, first ? 0 : 2);
                prefix = item.ordered() ? item.number() + ". " : "• ";
            } else {
                StyleConstants.setSpaceAbove(para, first ? 0 : 6);
            }
            int start = doc.getLength();
            try {
                if (!prefix.isEmpty()) {
                    SimpleAttributeSet marker = new SimpleAttributeSet();
                    StyleConstants.setFontFamily(marker, base.getFamily());
                    StyleConstants.setFontSize(marker, base.getSize());
                    StyleConstants.setForeground(marker, Ui.TEXT_SECONDARY);
                    doc.insertString(doc.getLength(), prefix, marker);
                }
                List<Span> spans = unit instanceof Paragraph p ? p.spans()
                        : unit instanceof Heading h ? h.spans() : ((ListItem) unit).spans();
                insertSpans(doc, spans, font, Ui.TEXT, unit instanceof Heading, false);
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
            doc.setParagraphAttributes(start, doc.getLength() - start, para, false);
            first = false;
        }
    }

    private static void fillQuote(StyledDocument doc, Quote quote, Font base) {
        for (int i = 0; i < quote.lines().size(); i++) {
            if (i > 0) {
                try {
                    doc.insertString(doc.getLength(), "\n", null);
                } catch (BadLocationException e) {
                    throw new IllegalStateException(e);
                }
            }
            int start = doc.getLength();
            try {
                insertSpans(doc, quote.lines().get(i), base, Ui.QUOTE_TEXT, false, false);
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
            SimpleAttributeSet para = new SimpleAttributeSet();
            StyleConstants.setSpaceAbove(para, i == 0 ? 0 : 3);
            doc.setParagraphAttributes(start, doc.getLength() - start, para, false);
        }
    }

    /**
     * 写入一行 span。字体族/字号必须显式写进属性：缺失时 StyledDocument 走默认值
     * （Dialog 12）而不是组件字体，标题放大和行内等宽都会失效。
     */
    private static void fillThink(StyledDocument doc, ThinkBlock think, Font base) {
        Font italic = base.deriveFont(Font.ITALIC);
        for (int i = 0; i < think.lines().size(); i++) {
            if (i > 0) {
                try {
                    doc.insertString(doc.getLength(), "\n", null);
                } catch (BadLocationException e) {
                    throw new IllegalStateException(e);
                }
            }
            int start = doc.getLength();
            try {
                insertSpans(doc, think.lines().get(i), italic, Ui.TEXT_SECONDARY, false, true);
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
            SimpleAttributeSet para = new SimpleAttributeSet();
            StyleConstants.setSpaceAbove(para, i == 0 ? 0 : 3);
            doc.setParagraphAttributes(start, doc.getLength() - start, para, false);
        }
    }

    /**
     * 写入一行 span。字体族/字号必须显式写进属性：缺失时 StyledDocument 走默认值
     * （Dialog 12）而不是组件字体，标题放大和行内等宽都会失效。italicBase 用于整行
     * 基线斜体（思考块）——同族字号要显式、斜体也一样不能依赖组件字体。
     */
    private static void insertSpans(StyledDocument doc, List<Span> spans, Font font, Color defaultFg,
                                    boolean boldBase, boolean italicBase) throws BadLocationException {
        for (Span span : spans) {
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            Font use = span.mono()
                    ? new Font("Monospaced", font.getStyle(), Math.max(11, font.getSize() - 1))
                    : font;
            StyleConstants.setFontFamily(attrs, use.getFamily());
            StyleConstants.setFontSize(attrs, use.getSize());
            if (boldBase || span.bold()) {
                StyleConstants.setBold(attrs, true);
            }
            if (italicBase || span.italic()) {
                StyleConstants.setItalic(attrs, true);
            }
            if (span.strike()) {
                StyleConstants.setStrikeThrough(attrs, true);
            }
            StyleConstants.setForeground(attrs, span.link() != null ? Ui.ACCENT
                    : span.mono() ? Ui.INLINE_CODE_FG : defaultFg);
            if (span.mono()) {
                StyleConstants.setBackground(attrs, Ui.INLINE_CODE_BG);
            }
            if (span.link() != null) {
                StyleConstants.setUnderline(attrs, true);
            }
            doc.insertString(doc.getLength(), span.text(), attrs);
        }
    }

    // ===== 解析 =====

    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d{1,9}[.)])[ \\t]+(\\S.*)$");

    /**
     * 解析入口。先把 &lt;think&gt;…&lt;/think&gt; 思考段切出来（流式时闭标可能还没到，
     * 吞到文本尾；游离的闭标剔除），两段之间的常规文本再走逐行解析。
     */
    public static List<Block> parse(String markdown) {
        List<Block> blocks = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            return blocks;
        }
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ");
        int idx = 0;
        while (true) {
            int open = text.indexOf("<think>", idx);
            if (open < 0) {
                blocks.addAll(parseBlocks(stripThinkClose(text.substring(idx))));
                return blocks;
            }
            blocks.addAll(parseBlocks(stripThinkClose(text.substring(idx, open))));
            int close = text.indexOf("</think>", open + 7);
            if (close < 0) {
                addThink(blocks, text.substring(open + 7), false);
                return blocks;
            }
            addThink(blocks, text.substring(open + 7, close), true);
            idx = close + 8;
        }
    }

    private static String stripThinkClose(String text) {
        return text.replace("</think>", "");
    }

    /** 思考段内容按行拆成 span，空段不产出块 */
    private static void addThink(List<Block> blocks, String content, boolean closed) {
        String trimmed = content.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        List<List<Span>> lines = new ArrayList<>();
        for (String line : trimmed.split("\n", -1)) {
            lines.add(inline(line.strip()));
        }
        blocks.add(new ThinkBlock(lines, closed));
    }

    /** 常规文本的逐行解析：围栏代码 > 标题 > 分隔线 > 引用 > 列表 > 段落；
     * 段落后面紧跟的 ---/=== 按 setext 规则提升为标题。 */
    private static List<Block> parseBlocks(String markdown) {
        List<Block> blocks = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            return blocks;
        }
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String trimmed = lines[i].strip();
            if (trimmed.isEmpty()) {
                i++;
                continue;
            }
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                i = parseFence(lines, i, blocks);
                continue;
            }
            int heading = headingLevel(trimmed);
            if (heading > 0) {
                String text = trimmed.substring(heading).strip().replaceFirst("\\s+#+\\s*$", "");
                blocks.add(new Heading(heading, inline(text)));
                i++;
                continue;
            }
            if (isRule(trimmed)) {
                blocks.add(new Rule());
                i++;
                continue;
            }
            // 表格（P1-5）：本行含竖线且下一行是 :---/--- 分隔行才算；表头在段落中间出现也要能接管
            if (trimmed.contains("|") && i + 1 < lines.length && isTableDelimiter(lines[i + 1])) {
                i = parseTable(lines, i, blocks);
                continue;
            }
            if (trimmed.startsWith(">")) {
                List<List<Span>> quoteLines = new ArrayList<>();
                while (i < lines.length && lines[i].strip().startsWith(">")) {
                    quoteLines.add(inline(lines[i].strip().replaceFirst("^>\\s?", "")));
                    i++;
                }
                blocks.add(new Quote(quoteLines));
                continue;
            }
            int[] item = listMarker(lines[i]);
            if (item != null) {
                String content = lines[i].substring(item[3]).strip()
                        .replaceFirst("^\\[[xX]\\]\\s+", "✓ ")
                        .replaceFirst("^\\[ \\]\\s+", "□ ");
                blocks.add(new ListItem(item[0], item[1] == 1, item[2], inline(content)));
                i++;
                continue;
            }
            List<String> para = new ArrayList<>();
            while (i < lines.length) {
                String t = lines[i].strip();
                if (t.isEmpty() || isRule(t) || headingLevel(t) > 0 || t.startsWith("```") || t.startsWith("~~~")
                        || t.startsWith(">") || listMarker(lines[i]) != null
                        || (t.contains("|") && i + 1 < lines.length && isTableDelimiter(lines[i + 1]))) {
                    break;
                }
                para.add(t);
                i++;
            }
            if (!para.isEmpty() && para.size() == 1 && i < lines.length) {
                String next = lines[i].strip();
                if (next.matches("={2,}")) {
                    blocks.add(new Heading(1, inline(para.get(0))));
                    i++;
                    continue;
                }
                if (next.matches("-{2,}")) {
                    blocks.add(new Heading(2, inline(para.get(0))));
                    i++;
                    continue;
                }
            }
            List<Span> spans = new ArrayList<>();
            for (int p = 0; p < para.size(); p++) {
                if (p > 0) {
                    spans.add(new Span("\n", false, false, false, false, null));
                }
                spans.addAll(inline(para.get(p)));
            }
            if (!spans.isEmpty()) {
                blocks.add(new Paragraph(spans));
            }
        }
        return blocks;
    }

    /** 解析表格（P1-5）：当前行=表头，下一行=分隔行，后续含竖线的行都是体行，
     *  空行/无竖线/围栏收束。对齐标记暂不解析。返回消费后的新行游标 */
    private static int parseTable(String[] lines, int headerIndex, List<Block> blocks) {
        List<List<List<Span>>> rows = new ArrayList<>();
        rows.add(parseTableRow(lines[headerIndex]));
        int i = headerIndex + 2; // 跳过分隔行
        while (i < lines.length) {
            String trimmed = lines[i].strip();
            if (trimmed.isEmpty() || !trimmed.contains("|")
                    || trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                break;
            }
            rows.add(parseTableRow(lines[i]));
            i++;
        }
        blocks.add(new TableBlock(rows));
        return i;
    }

    /** 表格行 → 单元格 span 列表：去首尾竖线（尾竖线须非转义）、\| 转义占位后按 | 切分，逐格行内解析 */
    private static List<List<Span>> parseTableRow(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String escaped = trimmed.replace("\\|", "\u0001");
        List<List<Span>> cells = new ArrayList<>();
        for (String raw : escaped.split("\\|", -1)) {
            cells.add(inline(raw.replace('\u0001', '|').strip()));
        }
        return cells;
    }

    /** 分隔行判定：| --- | :---: | 这类——去掉首尾竖线后每段都是可选冒号包着的连字符 */
    private static boolean isTableDelimiter(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        boolean anyCell = false;
        for (String raw : trimmed.split("\\|", -1)) {
            String cell = raw.strip();
            if (!cell.matches(":?-+:?")) {
                return false;
            }
            anyCell = true;
        }
        return anyCell;
    }

    /** 围栏代码块：从 start 行的 ``` 起，吞到闭栏或文件尾，返回新的行游标 */    private static int parseFence(String[] lines, int start, List<Block> blocks) {
        String open = lines[start].strip();
        String fenceChar = open.substring(0, 1);
        String info = open.replaceFirst("^[`~]+", "").strip();
        StringBuilder code = new StringBuilder();
        int i = start + 1;
        while (i < lines.length) {
            String t = lines[i].strip();
            if (t.startsWith(fenceChar) && t.replaceFirst("^[`~]+", "").isEmpty()) {
                i++; // 闭栏
                break;
            }
            code.append(lines[i]).append('\n');
            i++;
        }
        String language = info.isEmpty() ? "" : info.split("\\s+")[0];
        String text = code.toString();
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1); // 闭栏前的换行只标记结束，别渲染成空行
        }
        blocks.add(new CodeBlock(language, text));
        return i;
    }

    private static int headingLevel(String t) {
        int n = 0;
        while (n < t.length() && t.charAt(n) == '#') {
            n++;
        }
        if (n == 0 || n > 6) {
            return 0;
        }
        if (n == t.length()) {
            return n; // 整行只有 #，按空标题处理
        }
        return t.charAt(n) == ' ' ? n : 0; // "#标签" 不是标题
    }

    /** 三个及以上同种符号（可夹空格）视为分隔线：---、***、___ */
    private static boolean isRule(String t) {
        if (t.length() < 3) {
            return false;
        }
        char c = t.charAt(0);
        if (c != '-' && c != '*' && c != '_') {
            return false;
        }
        int count = 0;
        for (char x : t.toCharArray()) {
            if (x == c) {
                count++;
            } else if (x != ' ') {
                return false;
            }
        }
        return count >= 3;
    }

    /** 列表项：返回 {depth, ordered, number, 内容起始下标}，不是列表项返回 null */
    private static int[] listMarker(String line) {
        Matcher m = LIST_ITEM.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String marker = m.group(2);
        boolean ordered = Character.isDigit(marker.charAt(0));
        return new int[]{m.group(1).length() / 2, ordered ? 1 : 0,
                ordered ? Integer.parseInt(marker.substring(0, marker.length() - 1)) : 0, m.start(3)};
    }

    // ===== 行内解析 =====

    private record StyleState(boolean bold, boolean italic, boolean strike, String link) {
    }

    /** 行内标记解析：代码 > 链接/图片 > 裸 URL > 加粗/斜体/删除线 */
    public static List<Span> inline(String text) {
        List<Span> out = new ArrayList<>();
        parseInline(text == null ? "" : text, new StyleState(false, false, false, null), out);
        return out;
    }

    private static void parseInline(String s, StyleState st, List<Span> out) {
        StringBuilder plain = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!~>".indexOf(s.charAt(i + 1)) >= 0) {
                plain.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') { // 行内代码：内容原样保留，内部不再解析
                int open = runLength(s, i, '`');
                int close = indexOfRun(s, i + open, '`', open);
                if (close >= 0) {
                    flushPlain(plain, st, out);
                    out.add(new Span(s.substring(i + open, close), false, false, true, false, null));
                    i = close + open;
                    continue;
                }
            }
            if (c == '[' || (c == '!' && i + 1 < n && s.charAt(i + 1) == '[')) { // 链接/图片
                int labelStart = c == '!' ? i + 2 : i + 1;
                int closeBracket = findClosingBracket(s, labelStart);
                if (closeBracket < n && s.charAt(closeBracket) == ']'
                        && closeBracket + 1 < n && s.charAt(closeBracket + 1) == '(') {
                    int closeParen = s.indexOf(')', closeBracket + 2);
                    if (closeParen > closeBracket + 1) {
                        flushPlain(plain, st, out);
                        String url = s.substring(closeBracket + 2, closeParen).strip();
                        parseInline(s.substring(labelStart, closeBracket),
                                new StyleState(st.bold(), st.italic(), st.strike(),
                                        url.isEmpty() ? st.link() : url), out);
                        i = closeParen + 1;
                        continue;
                    }
                }
            }
            if (s.startsWith("https://", i) || s.startsWith("http://", i)) { // 裸 URL
                int end = i;
                while (end < n && !Character.isWhitespace(s.charAt(end)) && "\"<>".indexOf(s.charAt(end)) < 0) {
                    end++;
                }
                while (end > i && ".,;:!?))。，、；：！？）】》".indexOf(s.charAt(end - 1)) >= 0) { // 去尾标点（含中文标点）
                    end--;
                }
                flushPlain(plain, st, out);
                out.add(new Span(s.substring(i, end), st.bold(), st.italic(), false, st.strike(),
                        s.substring(i, end)));
                i = end;
                continue;
            }
            if (c == '*' || c == '_' || c == '~') { // 强调与删除线
                int run = runLength(s, i, c);
                int use = c == '~' ? 2 : Math.min(run, 3);
                // 边界检查只约束下划线：snake_case 里的 _ 不当斜体；* 和 ~ 贴着汉字照样生效
                boolean boundaryOk = c != '_' || i == 0 || !isWordChar(s.charAt(i - 1));
                int close = boundaryOk ? indexOfRun(s, i + use, c, use) : -1;
                boolean closeOk = c != '_' || close + use >= n || !isWordChar(s.charAt(close + use));
                if (close >= 0 && closeOk) {
                    flushPlain(plain, st, out);
                    boolean bold = c != '~' && use >= 2;
                    boolean italic = c != '~' && (use == 1 || use == 3);
                    parseInline(s.substring(i + use, close),
                            new StyleState(st.bold() || bold, st.italic() || italic,
                                    st.strike() || c == '~', st.link()), out);
                    i = close + use;
                    continue;
                }
            }
            plain.append(c);
            i++;
        }
        flushPlain(plain, st, out);
    }

    private static void flushPlain(StringBuilder plain, StyleState st, List<Span> out) {
        if (plain.length() > 0) {
            out.add(new Span(plain.toString(), st.bold(), st.italic(), false, st.strike(), st.link()));
            plain.setLength(0);
        }
    }

    private static int runLength(String s, int i, char c) {
        int j = i;
        while (j < s.length() && s.charAt(j) == c) {
            j++;
        }
        return j - i;
    }

    /** 从 from 起找第一个长度 >= len 的 c 连串，返回起点；没有返回 -1 */
    private static int indexOfRun(String s, int from, char c, int len) {
        for (int i = from; i + len <= s.length(); i++) {
            if (s.charAt(i) == c && runLength(s, i, c) >= len) {
                return i;
            }
        }
        return -1;
    }

    private static int findClosingBracket(String s, int from) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            } else if (c == '\\') {
                i++;
            }
        }
        return s.length(); // 没找到，调用方用 charAt 判断兜底
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}

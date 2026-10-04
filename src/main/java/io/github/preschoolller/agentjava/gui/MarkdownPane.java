package io.github.preschoolller.agentjava.gui;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 消息卡片的内容面板：把 Markdown 渲染结果（MarkdownRenderer.Row）纵向排布。
 * 布局是手动的——宽度变化时按行重新离屏测量（结果经 MeasureCache 按"文本+字号+宽度档位"缓存，
 * 流式重渲染与切会话同文本同宽度不重测），每行 setBounds 到全宽；显示组件从不参与测量，
 * 两条路径共用同一份渲染常量（踩坑记录 #2）。
 * 红线：首选宽度恒为 0，高度才写真实值——把内容宽写回首选宽会被 JViewport 撑出正反馈循环
 * （踩坑记录 #3），行组件的首选尺寸因此完全不被布局采用。
 *
 * 物化（P2-2 窗口化）：解析结果与行高数据常驻，行组件（JTextPane 等）只在卡片位于视口附近时
 * 才由 materialize 建出来；滚出视口 dematerialize 摘掉组件但保留数据，测量路径不受影响。
 * 长会话因此只有几十张卡片挂着真实组件。
 */
final class MarkdownPane extends JPanel {

    private static final int ROW_GAP = 6;
    /** 卡片完成首次布局前的兜底测量宽度，首帧后 fitCardHeight 会按真实宽度重测 */
    private static final int FALLBACK_WIDTH = 640;

    private final int fontSize;
    private final Font baseFont;
    private List<MarkdownRenderer.Row> rows = List.of();
    private final List<Component> rowComponents = new ArrayList<>();
    private String text = "";
    /** 当前测量所用的宽度档位（8px 向下取整），rowHeights 与之对应 */
    private int measuredWidth = -1;
    private int[] rowHeights = {};
    private int totalHeight;
    private boolean materialized;

    MarkdownPane(int fontSize) {
        super(null);
        setOpaque(false);
        this.fontSize = fontSize;
        this.baseFont = new Font("微软雅黑", Font.PLAIN, fontSize);
    }

    /** 当前渲染的原始 Markdown 文本（复制按钮用它拷原文） */
    String getText() {
        return text;
    }

    /** 替换内容并重测；已物化时同步重建行组件，流式输出每个节流周期调一次 */
    void setContent(String value) {
        this.text = value == null ? "" : value;
        rows = MarkdownRenderer.buildRows(MarkdownRenderer.parse(text), baseFont);
        if (measuredWidth > 0) {
            measure(measuredWidth); // 行数变了，按当前宽度档位重测
        }
        if (materialized) {
            rebuildRowComponents();
        }
        revalidate();
        repaint();
    }

    boolean isMaterialized() {
        return materialized;
    }

    /** 把行组件建出来挂到面板上；只有视口附近的卡片才调（窗口化） */
    void materialize() {
        if (materialized) {
            return;
        }
        materialized = true;
        rebuildRowComponents();
        revalidate();
        repaint();
    }

    /** 摘掉行组件（滚出视口）；解析结果与行高保留，滚回来再物化时无需重新测量 */
    void dematerialize() {
        if (!materialized) {
            return;
        }
        materialized = false;
        removeAll();
        rowComponents.clear();
    }

    private void rebuildRowComponents() {
        removeAll();
        rowComponents.clear();
        for (MarkdownRenderer.Row row : rows) {
            Component component = row.component(baseFont);
            rowComponents.add(component);
            add(component);
        }
    }

    /** 给定文本宽度下的内容总高度，按宽度档位缓存（fitCardHeight 每次重排都调，不许重复做 View 运算） */
    int preferredHeightAt(int width) {
        int w = MeasureCache.bucketWidth(width > 0 ? width : FALLBACK_WIDTH);
        if (w != measuredWidth) {
            measure(w);
        }
        return totalHeight;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(0, totalHeight); // 宽度恒 0，见类注释红线
    }

    @Override
    public void doLayout() {
        int width = getWidth();
        if (width <= 0) {
            return;
        }
        int w = MeasureCache.bucketWidth(width);
        if (w != measuredWidth) {
            measure(w);
        }
        int y = 0;
        for (int i = 0; i < rowComponents.size(); i++) {
            rowComponents.get(i).setBounds(0, y, width, rowHeights[i]);
            y += rowHeights[i] + ROW_GAP;
        }
    }

    private void measure(int width) {
        int[] cached = MeasureCache.get(text, fontSize, width, true);
        if (cached != null && cached.length == rows.size()) {
            rowHeights = cached;
        } else {
            rowHeights = new int[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                rowHeights[i] = rows.get(i).measureAt(baseFont, width);
            }
            MeasureCache.put(text, fontSize, width, true, rowHeights);
        }
        int total = 0;
        for (int height : rowHeights) {
            total += height + ROW_GAP;
        }
        totalHeight = rows.isEmpty() ? 0 : total - ROW_GAP;
        measuredWidth = width;
    }
}

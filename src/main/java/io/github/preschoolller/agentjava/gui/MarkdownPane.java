package io.github.preschoolller.agentjava.gui;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 消息卡片的内容面板：把 Markdown 渲染结果（MarkdownRenderer.Row）纵向排布。
 * 布局是手动的——宽度变化时按行重新离屏测量（结果按宽度缓存，流式重渲染同宽度不重测），
 * 每行 setBounds 到全宽；显示组件从不参与测量，两条路径共用同一份渲染常量（踩坑记录 #2）。
 * 红线：首选宽度恒为 0，高度才写真实值——把内容宽写回首选宽会被 JViewport 撑出正反馈循环
 * （踩坑记录 #3），行组件的首选尺寸因此完全不被布局采用。
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
    private int measuredWidth = -1;
    private int[] rowHeights = {};
    private int totalHeight;

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

    /** 替换内容并按当前宽度重排；流式输出每个节流周期调一次 */
    void setMarkdown(String value) {
        this.text = value == null ? "" : value;
        removeAll();
        rowComponents.clear();
        rows = MarkdownRenderer.buildRows(MarkdownRenderer.parse(text), baseFont);
        for (MarkdownRenderer.Row row : rows) {
            Component component = row.component(baseFont);
            rowComponents.add(component);
            add(component);
        }
        if (measuredWidth > 0) {
            measure(measuredWidth); // 行数变了，按当前宽度重测
        }
        revalidate();
        repaint();
    }

    /** 给定文本宽度下的内容总高度，按宽度缓存（fitCardHeight 每次重排都调，不许重复做 View 运算） */
    int preferredHeightAt(int width) {
        int w = width > 0 ? width : FALLBACK_WIDTH;
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
        if (width != measuredWidth) {
            measure(width);
        }
        int y = 0;
        for (int i = 0; i < rowComponents.size(); i++) {
            rowComponents.get(i).setBounds(0, y, width, rowHeights[i]);
            y += rowHeights[i] + ROW_GAP;
        }
    }

    private void measure(int width) {
        rowHeights = new int[rows.size()];
        int total = 0;
        for (int i = 0; i < rows.size(); i++) {
            rowHeights[i] = rows.get(i).measureAt(baseFont, width);
            total += rowHeights[i] + ROW_GAP;
        }
        totalHeight = rows.isEmpty() ? 0 : total - ROW_GAP;
        measuredWidth = width;
    }
}

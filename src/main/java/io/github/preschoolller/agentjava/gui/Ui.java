package io.github.preschoolller.agentjava.gui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.plaf.basic.BasicSpinnerUI;
import javax.swing.border.Border;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 统一配色与小组件：圆角面板 + 扁平按钮。
 * 最初照手绘图画的绿色边框、黑色粗边框已全部替换成这套浅色扁平风格，
 * 层次感靠背景深浅和卡片阴影式描边体现。
 */
final class Ui {

    /** 主区背景 */
    static final Color BG = new Color(0xF4F5F7);
    /** 侧栏背景 */
    static final Color SIDEBAR_BG = new Color(0xEDEFF2);
    /** 侧栏选中项背景 */
    static final Color SIDEBAR_SELECTED = new Color(0xDCE3ED);
    /** AI 消息卡片底色 */
    static final Color CARD_BG = Color.WHITE;
    /** 用户消息卡片底色，微微偏蓝方便扫读 */
    static final Color USER_CARD_BG = new Color(0xF3F7FF);
    /** 卡片描边 */
    static final Color BORDER = new Color(0xE3E5E9);
    /** 输入框描边 */
    static final Color BORDER_STRONG = new Color(0xD6D9DE);
    /** 主要文字 */
    static final Color TEXT = new Color(0x24292F);
    /** 次要文字（时间、占位提示等） */
    static final Color TEXT_SECONDARY = new Color(0x8A919F);
    /** 主题色：AI 角色名、主按钮、输入框聚焦 */
    static final Color ACCENT = new Color(0x3D7EFF);
    /** 主题色悬停 */
    static final Color ACCENT_PRESSED = new Color(0x2F66DB);
    /** 实时数据小胶囊底色 */
    static final Color PILL_BG = new Color(0xE9EBEF);
    /** 代码块底色与描边（AI 消息 Markdown 渲染） */
    static final Color CODE_BG = new Color(0xF6F8FA);
    static final Color CODE_BORDER = new Color(0xE2E6EB);
    /** 行内代码前景/底色 */
    static final Color INLINE_CODE_FG = new Color(0x9C3B57);
    static final Color INLINE_CODE_BG = new Color(0xF0F1F5);
    /** 引用块底色、左竖条与文字色 */
    static final Color QUOTE_BG = new Color(0xF7F8FA);
    static final Color QUOTE_BAR = new Color(0xD3D9E0);
    static final Color QUOTE_TEXT = new Color(0x5B6470);

    static final Font FONT_PLAIN = new Font("微软雅黑", Font.PLAIN, 12);
    static final Font FONT_BOLD = new Font("微软雅黑", Font.BOLD, 12);

    private Ui() {
    }

    /** 圆角面板：绘制圆角底色和（可选）描边；background/borderColor 可直接改后 repaint */
    static RoundPanel panel(int arc, Color background, Color borderColor) {
        return new RoundPanel(arc, background, borderColor);
    }

    /** 消息卡片：圆角白底细描边 */
    static RoundPanel card(Color background) {
        return panel(18, background, BORDER);
    }

    /** 输入控件的圆角边框（外圈描边 + 内边距） */
    static Border inputBorder(Color color) {
        return BorderFactory.createCompoundBorder(new RoundBorder(10, color),
                BorderFactory.createEmptyBorder(6, 10, 6, 10));
    }

    /** 给文本输入框套圆角边框，聚焦时描边变主题色 */
    static void styleInput(JTextComponent field) {
        field.setBorder(inputBorder(BORDER_STRONG));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                field.setBorder(inputBorder(ACCENT));
            }

            @Override
            public void focusLost(FocusEvent e) {
                field.setBorder(inputBorder(BORDER_STRONG));
            }
        });
    }

    /** 左右可拖拽的分栏：扁平分隔条，平时一条浅灰细线，悬停/拖动时变主题色 */
    static JSplitPane flatSplitPane(Component left, Component right) {
        JSplitPane pane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        pane.setContinuousLayout(true);
        pane.setOneTouchExpandable(false);
        pane.setResizeWeight(0);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.setDividerSize(6);
        pane.setUI(new BasicSplitPaneUI() {
            @Override
            public BasicSplitPaneDivider createDefaultDivider() {
                return new BasicSplitPaneDivider(this) {
                    private boolean hover;
                    private boolean pressed;
                    {
                        setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
                        addMouseListener(new MouseAdapter() {
                            @Override
                            public void mouseEntered(MouseEvent e) {
                                hover = true;
                                repaint();
                            }

                            @Override
                            public void mouseExited(MouseEvent e) {
                                hover = false;
                                repaint();
                            }

                            @Override
                            public void mousePressed(MouseEvent e) {
                                pressed = true;
                                repaint();
                            }

                            @Override
                            public void mouseReleased(MouseEvent e) {
                                pressed = false;
                                repaint();
                            }
                        });
                    }

                    @Override
                    public void paint(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(SIDEBAR_BG);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        g2.setColor(hover || pressed ? ACCENT : new Color(0xE0E3E8));
                        g2.fillRect(getWidth() / 2, 0, 1, getHeight());
                        g2.dispose();
                    }
                };
            }
        });
        return pane;
    }

    /** 微调框（JSpinner）：圆角边框、左对齐数值、右侧一对细小的上下箭头 */
    static void styleSpinner(JSpinner spinner) {
        spinner.setUI(new BasicSpinnerUI() {
            @Override
            protected Component createNextButton() {
                JButton button = spinnerButton("▲");
                installNextButtonListeners(button);
                return button;
            }

            @Override
            protected Component createPreviousButton() {
                JButton button = spinnerButton("▼");
                installPreviousButtonListeners(button);
                return button;
            }
        });
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            JTextField field = editor.getTextField();
            field.setHorizontalAlignment(JTextField.LEFT);
            field.setFont(FONT_PLAIN);
            field.setForeground(TEXT);
            field.setBackground(Color.WHITE);
            field.setOpaque(true);
            field.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 2));
            // Metal 会给编辑区自带一层灰色边框/底色，清掉后透出白底
            editor.setBorder(BorderFactory.createEmptyBorder());
        }
        spinner.setBorder(new RoundBorder(10, BORDER_STRONG));
        spinner.setBackground(Color.WHITE);
        spinner.setPreferredSize(new Dimension(160, 32));
    }

    /** 微调框的小箭头按钮：透明底、灰色字符箭头，悬停变主题色 */
    private static JButton spinnerButton(String glyph) {
        JButton button = new JButton(glyph) {
            private boolean hover;
            {
                setContentAreaFilled(false);
                setFocusPainted(false);
                setBorder(BorderFactory.createEmptyBorder());
                setOpaque(false);
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = false;
                        repaint();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                setForeground(hover ? ACCENT : TEXT_SECONDARY);
                super.paintComponent(g);
            }
        };
        button.setFont(new Font("微软雅黑", Font.PLAIN, 9));
        button.setPreferredSize(new Dimension(18, 16));
        button.setFocusable(false);
        return button;
    }

    /** 把滚动面板的纵横滚动条都换成现代细滚动条 */
    static void styleScrollPane(JScrollPane pane) {
        styleScrollBar(pane.getVerticalScrollBar());
        styleScrollBar(pane.getHorizontalScrollBar());
    }

    /** 现代滚动条：无箭头按钮、透明轨道、圆角细拇指，悬停加深 */
    static void styleScrollBar(JScrollBar bar) {
        bar.setUI(new ModernScrollBarUI());
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(8, 8));
    }

    /** 滚动条拇指配色：常态浅灰，悬停/拖动加深 */
    private static final Color SCROLL_THUMB = new Color(0xC7CCD5);
    private static final Color SCROLL_THUMB_ACTIVE = new Color(0xA6AEBB);

    private static class ModernScrollBarUI extends BasicScrollBarUI {

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private JButton zeroButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            button.setFocusable(false);
            return button;
        }

        @Override
        protected Dimension getMinimumThumbSize() {
            // 拇指太小时点不中，保底 30px
            return new Dimension(30, 30);
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            // 轨道透明，透出滚动区自己的浅色背景
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isThumbRollover() || isDragging ? SCROLL_THUMB_ACTIVE : SCROLL_THUMB);
            if (scrollbar.getOrientation() == JScrollBar.VERTICAL) {
                g2.fillRoundRect(thumbBounds.x + 2, thumbBounds.y + 1,
                        thumbBounds.width - 4, thumbBounds.height - 2, 5, 5);
            } else {
                g2.fillRoundRect(thumbBounds.x + 1, thumbBounds.y + 2,
                        thumbBounds.width - 2, thumbBounds.height - 4, 5, 5);
            }
            g2.dispose();
        }
    }

    /** 扁平按钮：白底圆角细边框，悬停微微变灰 */
    static JButton button(String text) {
        JButton button = new JButton(text) {
            private boolean hover;
            {
                setContentAreaFilled(false);
                setFocusPainted(false);
                setBorder(BorderFactory.createEmptyBorder());
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = false;
                        repaint();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hover ? new Color(0xF0F2F6) : Color.WHITE);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.setColor(new Color(0xD6D9DE));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setFont(FONT_PLAIN);
        button.setForeground(TEXT);
        return button;
    }

    /** 主按钮：主题色圆角底 + 白字 */
    static JButton primaryButton(String text) {
        JButton button = new JButton(text) {
            private boolean hover;
            {
                setContentAreaFilled(false);
                setFocusPainted(false);
                setBorder(BorderFactory.createEmptyBorder());
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hover = false;
                        repaint();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hover ? ACCENT_PRESSED : ACCENT);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setFont(FONT_BOLD);
        button.setForeground(Color.WHITE);
        return button;
    }

    /** 圆角面板实现：字段开放，方便外部（如输入框聚焦）改描边色后 repaint */
    static class RoundPanel extends JPanel {
        final int arc;
        final Color background;
        Color borderColor;

        RoundPanel(int arc, Color background, Color borderColor) {
            this.arc = arc;
            this.background = background;
            this.borderColor = borderColor;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(background);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            if (borderColor != null) {
                g2.setColor(borderColor);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            g2.dispose();
        }
    }

    /** 圆角描边，内边距为零，配合 CompoundBorder 里的空白内衬使用 */
    static class RoundBorder implements Border {
        private final int arc;
        private final Color color;

        RoundBorder(int arc, Color color) {
            this.arc = arc;
            this.color = color;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.drawRoundRect(x, y, width - 1, height - 1, arc, arc);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(2, 2, 2, 2);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }
    }
}

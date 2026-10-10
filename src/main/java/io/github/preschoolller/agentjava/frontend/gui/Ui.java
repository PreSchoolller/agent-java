package io.github.preschoolller.agentjava.frontend.gui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicMenuItemUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.plaf.basic.BasicSpinnerUI;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.border.Border;
import javax.swing.text.JTextComponent;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Function;

/**
 * 统一配色与小组件：圆角面板 + 扁平按钮。
 * 最初照手绘图画的绿色边框、黑色粗边框已全部替换成这套扁平风格，
 * 层次感靠背景深浅和卡片阴影式描边体现。
 * 颜色常量是"当前生效配色"的镜像，真正的色值在 Theme（浅/深两套，P2-1）：
 * applyTheme 切换镜像后，构造期捕获了颜色的组件由 Window 重建界面换装；
 * paintComponent 里现场读常量的自绘组件（按钮/滚动条/分隔条）重绘即生效。
 * 新增颜色三处同步：Theme.light()/dark() 各一行 + 下面声明 + applyTheme 镜像。
 */
final class Ui {

    /** 当前生效的色板 */
    private static Theme theme;

    /** 主区背景 */
    static Color BG;
    /** 侧栏背景 */
    static Color SIDEBAR_BG;
    /** 侧栏选中项背景 */
    static Color SIDEBAR_SELECTED;
    /** 消息卡片底色 */
    static Color CARD_BG;
    /** 用户消息卡片底色，微微偏蓝方便扫读 */
    static Color USER_CARD_BG;
    /** 卡片描边 */
    static Color BORDER;
    /** 输入框描边 */
    static Color BORDER_STRONG;
    /** 主要文字 */
    static Color TEXT;
    /** 次要文字（时间、占位提示等） */
    static Color TEXT_SECONDARY;
    /** 主题色：AI 角色名、主按钮、输入框聚焦 */
    static Color ACCENT;
    /** 主题色悬停 */
    static Color ACCENT_PRESSED;
    /** 实时数据小胶囊底色 */
    static Color PILL_BG;
    /** 代码块底色与描边（AI 消息 Markdown 渲染） */
    static Color CODE_BG;
    static Color CODE_BORDER;
    /** 行内代码前景/底色 */
    static Color INLINE_CODE_FG;
    static Color INLINE_CODE_BG;
    /** diff 块增/删行前景与行底色 */
    static Color DIFF_ADD_FG;
    static Color DIFF_ADD_BG;
    static Color DIFF_DEL_FG;
    static Color DIFF_DEL_BG;
    /** 引用块底色、左竖条与文字色 */
    static Color QUOTE_BG;
    static Color QUOTE_BAR;
    static Color QUOTE_TEXT;
    /** 思考块（<think> 推理内容）底色与描边 */
    static Color THINK_BG;
    static Color THINK_BORDER;
    /** 弹出层（菜单/下拉列表）描边与底色 */
    static Color POPUP_BORDER;
    static Color MENU_BG;
    /** 菜单项/下拉列表选中底色 */
    static Color MENU_SELECTED;
    /** 输入控件（文本框/微调框/下拉框值区）底色 */
    static Color INPUT_BG;
    /** 按钮常态/悬停底色 */
    static Color BUTTON_BG;
    static Color BUTTON_HOVER_BG;
    /** 分隔线：侧栏右缘、分栏分隔条、Markdown 水平线 */
    static Color DIVIDER_LINE;
    /** 搜索命中词高亮底色（侧栏 HTML） */
    static Color MARK_BG;
    /** 危险操作文字（删除类菜单项） */
    static Color DANGER;
    /** 危险按钮悬停/按下底色 */
    static Color DANGER_PRESSED;
    /** 警示文字：工具卡"待确认"徽标这类未出错的提醒（P0-4 权限确认卡） */
    static Color WARNING;
    /** 滚动条拇指配色：常态浅灰，悬停/拖动加深 */
    static Color SCROLL_THUMB;
    static Color SCROLL_THUMB_ACTIVE;

    static final Font FONT_PLAIN = new Font("微软雅黑", Font.PLAIN, 12);
    static final Font FONT_BOLD = new Font("微软雅黑", Font.BOLD, 12);

    static {
        applyTheme("light");
    }

    private Ui() {
    }

    /** 切换配色（"light"/"dark"，其他值回落浅色）：更新镜像；组件换装靠 Window 重建界面 */
    static void applyTheme(String name) {
        theme = "dark".equals(name) ? Theme.DARK : Theme.LIGHT;
        BG = theme.bg;
        SIDEBAR_BG = theme.sidebarBg;
        SIDEBAR_SELECTED = theme.sidebarSelected;
        CARD_BG = theme.cardBg;
        USER_CARD_BG = theme.userCardBg;
        BORDER = theme.border;
        BORDER_STRONG = theme.borderStrong;
        TEXT = theme.text;
        TEXT_SECONDARY = theme.textSecondary;
        ACCENT = theme.accent;
        ACCENT_PRESSED = theme.accentPressed;
        PILL_BG = theme.pillBg;
        CODE_BG = theme.codeBg;
        CODE_BORDER = theme.codeBorder;
        INLINE_CODE_FG = theme.inlineCodeFg;
        INLINE_CODE_BG = theme.inlineCodeBg;
        DIFF_ADD_FG = theme.diffAddFg;
        DIFF_ADD_BG = theme.diffAddBg;
        DIFF_DEL_FG = theme.diffDelFg;
        DIFF_DEL_BG = theme.diffDelBg;
        QUOTE_BG = theme.quoteBg;
        QUOTE_BAR = theme.quoteBar;
        QUOTE_TEXT = theme.quoteText;
        THINK_BG = theme.thinkBg;
        THINK_BORDER = theme.thinkBorder;
        POPUP_BORDER = theme.popupBorder;
        MENU_BG = theme.menuBg;
        MENU_SELECTED = theme.menuSelected;
        INPUT_BG = theme.inputBg;
        BUTTON_BG = theme.buttonBg;
        BUTTON_HOVER_BG = theme.buttonHoverBg;
        DIVIDER_LINE = theme.dividerLine;
        MARK_BG = theme.markBg;
        DANGER = theme.danger;
        DANGER_PRESSED = theme.dangerPressed;
        WARNING = theme.warning;
        SCROLL_THUMB = theme.scrollThumb;
        SCROLL_THUMB_ACTIVE = theme.scrollThumbActive;
        // 提示气泡跟随主题；轻量弹层每次 show 时重建，下一次生效
        UIManager.put("ToolTip.background", MENU_BG);
        UIManager.put("ToolTip.foreground", TEXT);
        // 禁用下拉的值区走 DefaultLookup 的 disabledBackground/Foreground（默认白底，
        // 与组件背景无关——踩坑 #18 同源）。disabledBackground 给和值区一致的输入底色，
        // 给别的色会透出一块更深的"垫子"（渲染器 label 是不透明的，按自身背景整块填）
        UIManager.put("ComboBox.disabledBackground", INPUT_BG);
        UIManager.put("ComboBox.disabledForeground", TEXT_SECONDARY);
    }

    /**
     * 程序化应用图标：主题色圆角底 + 白色 "A"（与托盘图标同一份绘制，窗口图标/托盘共用）。
     * 不引资源文件，几何参数按尺寸等比缩放。
     */
    static java.awt.image.BufferedImage createAppIcon(int size) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(ACCENT);
        g2.fillRoundRect(1, 1, size - 2, size - 2, size * 2 / 5, size * 2 / 5);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("微软雅黑", Font.BOLD, Math.max(8, size * 2 / 3)));
        FontMetrics metrics = g2.getFontMetrics();
        g2.drawString("A", (size - metrics.stringWidth("A")) / 2,
                (size - metrics.getHeight()) / 2 + metrics.getAscent());
        g2.dispose();
        return image;
    }

    /** 颜色转 HTML 十六进制（侧栏高亮等 Swing HTML 场景用，见踩坑 #17） */
    static String hex(Color color) {
        return String.format("#%06x", color.getRGB() & 0xFFFFFF);
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

    /** 给文本输入框套圆角边框，聚焦时描边变主题色；底色/文字/光标显式设过，深色下才不会黑字黑光标 */
    static void styleInput(JTextComponent field) {
        field.setBorder(inputBorder(BORDER_STRONG));
        field.setBackground(INPUT_BG);
        field.setForeground(TEXT);
        field.setCaretColor(TEXT);
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
                        g2.setColor(hover || pressed ? ACCENT : DIVIDER_LINE);
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
            field.setCaretColor(TEXT);
            field.setBackground(INPUT_BG);
            field.setOpaque(true);
            field.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 2));
            // Metal 会给编辑区自带一层灰色边框/底色，清掉后透出主题底色
            editor.setBorder(BorderFactory.createEmptyBorder());
        }
        spinner.setBorder(new RoundBorder(10, BORDER_STRONG));
        spinner.setBackground(INPUT_BG);
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

    /** 弹出菜单：弹层底色、浅描边、四周留 4px 内衬（选中高亮与描边不贴边） */
    static void stylePopupMenu(JPopupMenu menu) {
        menu.setBackground(MENU_BG);
        menu.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(POPUP_BORDER),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
    }

    /** 现代菜单项：弹层底色、圆角悬停底色、无虚线焦点框；danger 项（删除类）文字用警示红 */
    static JMenuItem menuItem(String text) {
        return menuItem(text, false);
    }

    static JMenuItem menuItem(String text, boolean danger) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(FONT_PLAIN);
        item.setForeground(danger ? DANGER : TEXT);
        item.setBackground(MENU_BG);
        item.setFocusPainted(false);
        item.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 28));
        item.setUI(new BasicMenuItemUI() {
            {
                selectionBackground = MENU_SELECTED;
                selectionForeground = item.getForeground();
            }

            @Override
            protected void paintBackground(Graphics g, JMenuItem menuItem, Color bgColor) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(menuItem.getBackground());
                g2.fillRect(0, 0, menuItem.getWidth(), menuItem.getHeight());
                if (menuItem.isArmed()) {
                    g2.setColor(selectionBackground);
                    g2.fillRoundRect(2, 1, menuItem.getWidth() - 4, menuItem.getHeight() - 2, 8, 8);
                }
                g2.dispose();
            }
        });
        return item;
    }

    /** 下拉框：圆角输入框同款描边，右侧细箭头复用微调框的按钮，弹出列表跟随弹层配色 */
    static void styleComboBox(JComboBox<?> combo) {
        // installColors 会用 Metal 的 ComboBox.background（#EEEEEE）盖掉先前设的底色，
        // setBackground 必须放在 setUI 之后
        combo.setUI(new BasicComboBoxUI() {
            @Override
            protected ComboPopup createPopup() {
                ComboPopup popup = super.createPopup();
                if (popup instanceof JComponent component) {
                    component.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(POPUP_BORDER),
                            BorderFactory.createEmptyBorder(3, 3, 3, 3)));
                    component.setBackground(MENU_BG);
                }
                return popup;
            }

            @Override
            protected JButton createArrowButton() {
                JButton button = spinnerButton("▼");
                button.setPreferredSize(new Dimension(20, 14));
                return button;
            }

            @Override
            public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) {
                // JDK 的默认实现直接取 UI 默认 "ComboBox.background"（Metal 下 #EEEEEE）填值区，
                // 无视组件实际背景（见踩坑 #18）；这里改成跟随组件背景
                g.setColor(comboBox.getBackground());
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }

            @Override
            public void paintCurrentValue(Graphics g, Rectangle bounds, boolean hasFocus) {
                super.paintCurrentValue(g, bounds, false); // 聚焦时不画整条选中底色，白底黑字到底
            }
        });
        combo.setFont(FONT_PLAIN);
        combo.setForeground(TEXT);
        combo.setBackground(INPUT_BG);
        combo.setBorder(inputBorder(BORDER_STRONG));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                label.setFont(FONT_PLAIN);
                label.setForeground(TEXT);
                label.setBackground(selected ? MENU_SELECTED : MENU_BG);
                label.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
                return label;
            }
        });
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

    /** 扁平按钮：圆角细边框，悬停微微变色 */
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
                // 现场读镜像常量：换主题后重绘即生效，不用等重建
                g2.setColor(hover ? BUTTON_HOVER_BG : BUTTON_BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.setColor(BORDER_STRONG);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setFont(FONT_PLAIN);
        button.setForeground(TEXT);
        return button;
    }

    /**
     * 展示型文本面板（不可编辑、不透出底色、不参与焦点）。卡片正文这类组件嵌在滚动容器里，
     * 光标的 adjustVisibility 会在组件 bounds 未就绪时把外层滚动容器一路拽到光标处（见踩坑 #20），
     * 因此直接禁掉 scrollRectToVisible——纯展示组件没有任何需要滚到可见的场景。
     * editable/opaque 必须在这里设全：调用方各自补容易漏（漏了 opaque 就是白底方块，浅色下几乎看不出）。
     */
    static JTextPane displayTextPane() {
        JTextPane pane = new JTextPane() {
            @Override
            public void scrollRectToVisible(Rectangle rect) {
            }
        };
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setFocusable(false);
        return pane;
    }

    /** 同 displayTextPane：给代码块等展示型 JTextArea 用 */
    static JTextArea displayTextArea() {
        JTextArea area = new JTextArea() {
            @Override
            public void scrollRectToVisible(Rectangle rect) {
            }
        };
        area.setEditable(false);
        area.setOpaque(false);
        area.setFocusable(false);
        return area;
    }

    /** 扁平勾选框：圆角方框，未选中为输入底色 + 描边，选中为主题色底 + 白色对勾 */
    static void styleCheckBox(JCheckBox box) {
        box.setOpaque(false);
        box.setFont(FONT_PLAIN);
        box.setForeground(TEXT); // 默认黑字，深色下隐形（踩坑 #19）
        box.setFocusPainted(false);
        box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        box.setIcon(checkBoxIcon(false));
        box.setSelectedIcon(checkBoxIcon(true));
    }

    private static Icon checkBoxIcon(boolean checked) {
        int size = 15;
        return new Icon() {
            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(checked ? ACCENT : INPUT_BG);
                g2.fillRoundRect(x, y, size - 1, size - 1, 5, 5);
                g2.setColor(checked ? ACCENT : BORDER_STRONG);
                g2.drawRoundRect(x, y, size - 1, size - 1, 5, 5);
                if (checked) {
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(x + 4, y + 8, x + 7, y + 11);
                    g2.drawLine(x + 7, y + 11, x + 11, y + 4);
                }
                g2.dispose();
            }
        };
    }

    /** 主按钮：主题色圆角底 + 白字 */
    static JButton primaryButton(String text) {        JButton button = new JButton(text) {
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

    /** 危险按钮：警示红圆角底 + 白字（删除等不可逆操作的确认键） */
    static JButton dangerButton(String text) {
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
                g2.setColor(hover ? DANGER_PRESSED : DANGER);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setFont(FONT_BOLD);
        button.setForeground(Color.WHITE);
        return button;
    }

    /**
     * 主题化确认对话框（替代 JOptionPane.showConfirmDialog 的 Metal 系统样式）：
     * 确定返回 true；danger=true 时确定键用警示红（删除等不可逆操作）。
     * 必须在 EDT 上调用。
     */
    static boolean confirmDialog(Component parent, String title, String message, String okText, boolean danger) {
        boolean[] result = {false};
        JLabel messageLabel = new JLabel(message);
        messageLabel.setFont(FONT_PLAIN);
        messageLabel.setForeground(TEXT);
        JButton okButton = danger ? dangerButton(okText) : primaryButton(okText);
        JButton cancelButton = button("取消");
        okButton.setPreferredSize(new Dimension(70, 28));
        cancelButton.setPreferredSize(new Dimension(70, 28));

        Window owner = parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        okButton.addActionListener(e -> {
            result[0] = true;
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancelButton);
        buttons.add(okButton);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(BG);
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        content.add(messageLabel, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(320, dialog.getPreferredSize().height));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return result[0];
    }

    /**
     * 主题化输入对话框（替代 JOptionPane.showInputDialog 的 Metal 系统样式）：
     * 校验函数返回错误文案内联显示，回车=确定；取消返回 null。validator 可为 null。
     * 必须在 EDT 上调用。
     */
    static String promptDialog(Component parent, String title, String message, String initial,
            Function<String, String> validator) {
        String[] result = {null};
        JTextField field = new JTextField(initial);
        styleInput(field);
        field.setPreferredSize(new Dimension(280, 30));
        JLabel messageLabel = new JLabel(message);
        messageLabel.setFont(FONT_PLAIN);
        messageLabel.setForeground(TEXT);
        JLabel hint = new JLabel(" ");
        hint.setFont(FONT_PLAIN);
        hint.setForeground(DANGER);
        JButton okButton = primaryButton("确定");
        JButton cancelButton = button("取消");
        okButton.setPreferredSize(new Dimension(70, 28));
        cancelButton.setPreferredSize(new Dimension(70, 28));

        Window owner = parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        okButton.addActionListener(e -> {
            String value = field.getText().strip();
            String error = validator == null ? null : validator.apply(value);
            if (error == null) {
                result[0] = value;
                dialog.dispose();
            } else {
                hint.setText(error);
                dialog.pack();
                field.requestFocusInWindow();
            }
        });
        cancelButton.addActionListener(e -> dialog.dispose());
        field.addActionListener(e -> okButton.doClick()); // 回车=确定

        JPanel fields = new JPanel(new BorderLayout(0, 6));
        fields.setOpaque(false);
        fields.add(messageLabel, BorderLayout.NORTH);
        fields.add(field, BorderLayout.CENTER);
        fields.add(hint, BorderLayout.SOUTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancelButton);
        buttons.add(okButton);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(BG);
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        content.add(fields, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(360, dialog.getPreferredSize().height));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return result[0];
    }

    /** 主题化消息对话框（替代 JOptionPane.showMessageDialog）：单个确定键 */
    static void messageDialog(Component parent, String title, String message) {
        JLabel messageLabel = new JLabel(message);
        messageLabel.setFont(FONT_PLAIN);
        messageLabel.setForeground(TEXT);
        JButton okButton = primaryButton("确定");
        okButton.setPreferredSize(new Dimension(70, 28));

        Window owner = parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        okButton.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(okButton);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(BG);
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));
        content.add(messageLabel, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(320, dialog.getPreferredSize().height));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
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

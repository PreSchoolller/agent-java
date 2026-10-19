package io.github.preschoolller.agentjava.frontend.gui;

import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import io.github.preschoolller.agentjava.frontend.store.Profile;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 设置面板（模态对话框）：编辑 AppSettings 并持久化。
 * 顶部标签页分两页（胶囊切页，当前页主题色实心）：
 * 「通用」= 全局对话体验（主题/正文字号/关闭按钮）；
 * 「模型服务」= 配置方案的全部参数（方案管理/后端地址/Key/API 类型/模型/温度/最大 Tokens/系统提示词）。
 * 白色圆角卡片承载，与主界面同一套风格；系统提示词是每套方案自己的值（落 Profile），
 * 随方案在模型服务页编辑。
 * "模型服务"页首行是配置方案管理（下拉切换 + 新建/重命名/删除）：面板里的一切改动
 * （含方案增删改名）都落在深拷贝的工作副本上，点"保存"才整体提交；切换下拉或取消即放弃。
 * 保存后 applyProfiles（落盘 + fireChanged），后端可以在 AppSettings 的监听里感知配置变化；
 * 主窗口通过 isSaved() 判断是否需要按新设置（如正文字号）重建界面。
 */
public class SettingsDialog extends JDialog {

    private final AppSettings settings = AppSettings.get();
    /** 工作副本：面板里的新建/删除/改名/改参数都改它，取消直接丢弃 */
    private final LinkedHashMap<String, Profile> working;
    /** 下拉当前选中的方案名（未保存前可能与 settings 的活动方案不一致） */
    private String selectedName;
    /** 填充下拉期间压住切换事件 */
    private boolean suppressProfileSwitch;

    private final JComboBox<String> profileCombo = new JComboBox<>();
    /** 配色主题：索引 0 浅色 / 1 深色，保存时写回 AppSettings，由主窗口重建生效 */
    private final JComboBox<String> themeCombo = new JComboBox<>(new String[]{"跟随系统", "浅色", "深色"});
    /** 关闭按钮最小化到托盘（系统不支持托盘时勾了也只会直接退出） */
    private final JCheckBox trayCheckBox = new JCheckBox("最小化到托盘");
    private final JButton profileAddButton = Ui.button("新建");
    private final JButton profileRenameButton = Ui.button("重命名");
    private final JButton profileDeleteButton = Ui.button("删除");
    private final JTextField backendUrlField = new JTextField();
    private final JTextField keyField = new JTextField();
    /** 后端 API 类型（协议分派由后端实现，当前仅 response 生效） */
    private final JComboBox<String> apiTypeCombo = new JComboBox<>(Profile.API_TYPES.toArray(new String[0]));
    /** 模型编辑：只读下拉（选中项=输入区默认模型）+ 添加/删除按钮 */
    private final JComboBox<String> modelCombo = new JComboBox<>();
    private final JButton modelAddButton = Ui.button("添加");
    private final JButton modelDeleteButton = Ui.button("删除");
    private final JSpinner temperatureSpinner;
    private final JSpinner maxTokensSpinner;
    private final JSpinner fontSizeSpinner;
    private final JTextArea systemPromptArea = new JTextArea(6, 24);
    private boolean saved;

    /** 标签页 id：通用（全局对话体验）/ 模型服务（配置方案参数）；默认页=模型服务（原首屏内容） */
    private static final String TAB_GENERAL = "general";
    private static final String TAB_PROFILE = "profile";
    private String currentTab = TAB_PROFILE;
    private JPanel tabsPanel;
    private JPanel pagesPanel;
    private CardLayout pageLayout;

    public SettingsDialog(Frame owner) {
        this(owner, true);
    }

    public SettingsDialog(Frame owner, boolean modal) {
        super(owner, "设置", modal);

        working = settings.copyProfiles();
        selectedName = working.containsKey(settings.getActiveProfileName())
                ? settings.getActiveProfileName()
                : working.keySet().iterator().next();

        temperatureSpinner = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 2.0, 0.1));
        maxTokensSpinner = new JSpinner(new SpinnerNumberModel(64, 64, 1048576, 64));
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(settings.getContentFontSize(), 12, 22, 1));
        // token 数去掉千分位逗号：换用无分组 pattern 的 editor（直接改 format 不会刷新已渲染文本）
        maxTokensSpinner.setEditor(new JSpinner.NumberEditor(maxTokensSpinner, "#"));

        loadSelectedProfile();
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        systemPromptArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        systemPromptArea.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        // 没设过前景/光标色的文本组件默认黑字黑光标，深色下隐形（见踩坑 #19）
        systemPromptArea.setBackground(Ui.INPUT_BG);
        systemPromptArea.setForeground(Ui.TEXT);
        systemPromptArea.setCaretColor(Ui.TEXT);
        themeCombo.setSelectedIndex(switch (settings.getUiTheme()) {
            case "dark" -> 2;
            case "system" -> 0;
            default -> 1;
        });
        trayCheckBox.setSelected(settings.isMinimizeToTray());
        Ui.styleCheckBox(trayCheckBox);
        trayCheckBox.setToolTipText("系统不支持托盘时，关闭按钮仍会直接退出");

        Ui.styleInput(backendUrlField);
        Ui.styleInput(keyField);
        Ui.styleSpinner(temperatureSpinner);
        Ui.styleSpinner(maxTokensSpinner);
        Ui.styleSpinner(fontSizeSpinner);
        Ui.styleComboBox(profileCombo);
        Ui.styleComboBox(apiTypeCombo);
        apiTypeCombo.setToolTipText("后端 API 协议：response=OpenAI Responses（当前实现）；"
                + "chatcompletion=OpenAI Chat Completions、messages=Anthropic Messages 待后端支持");
        apiTypeCombo.setPreferredSize(new Dimension(150, 30));
        Ui.styleComboBox(themeCombo);
        themeCombo.setPreferredSize(new Dimension(150, 30));
        profileCombo.setPreferredSize(new Dimension(150, 30));
        profileAddButton.setPreferredSize(new Dimension(58, 26));
        profileRenameButton.setPreferredSize(new Dimension(70, 26));
        profileDeleteButton.setPreferredSize(new Dimension(58, 26));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.BG);

        // 顶部标签页条：两组胶囊切页。选中=主题色实心+白字，未选中=透底+次要字（悬停浅底），
        // 配色现场读镜像，与 Ui.button 同一约定
        tabsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 8));
        tabsPanel.setBackground(Ui.BG);
        tabsPanel.setBorder(BorderFactory.createEmptyBorder(0, 18, 4, 18));
        tabsPanel.add(new TabButton("通用", TAB_GENERAL));
        tabsPanel.add(new TabButton("模型服务", TAB_PROFILE));

        // 两页内容（CardLayout 切换）：每页各自套滚动容器——原先是两卡同屏共用一份
        // groupsScroll（装不下出滚动条的挤压重排修法），分页后短页不再继承长页的滚动空间。
        // 页高取两者较大者：切换标签时对话框尺寸不变，行也不被挤压重排
        pageLayout = new CardLayout();
        pagesPanel = new JPanel(pageLayout);
        pagesPanel.setOpaque(false);
        pagesPanel.add(wrapPage(buildGeneralPage()), TAB_GENERAL);
        pagesPanel.add(wrapPage(buildProfilePage()), TAB_PROFILE);
        pageLayout.show(pagesPanel, currentTab);

        JButton saveButton = Ui.primaryButton("保存");
        JButton cancelButton = Ui.button("取消");
        saveButton.setPreferredSize(new Dimension(76, 32));
        cancelButton.setPreferredSize(new Dimension(76, 32));
        saveButton.addActionListener(e -> save());
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 12));
        buttons.setBackground(Ui.BG);
        buttons.add(cancelButton);
        buttons.add(saveButton);

        root.add(tabsPanel, BorderLayout.NORTH);
        root.add(pagesPanel, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);

        // 高度跟随内容首选：pack 拿全量（两页的较高者，保底 700 换页不跳尺寸），小屏按屏高九成
        // 截断——超出部分由各页的滚动容器接管，行高永远按首选渲染
        pack();
        int screenHeight = Toolkit.getDefaultToolkit().getScreenSize().height;
        setSize(560, Math.min(Math.max(getHeight(), 700), screenHeight * 9 / 10));
        setLocationRelativeTo(owner);
    }

    /** 通用页：全局对话体验（主题/正文字号/关闭按钮托盘） */
    private JPanel buildGeneralPage() {
        JPanel groups = new JPanel();
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        groups.setBackground(Ui.BG);
        groups.setBorder(BorderFactory.createEmptyBorder(6, 18, 4, 18));

        JPanel chatCard = Ui.card(Ui.CARD_BG);
        chatCard.setLayout(new GridBagLayout());
        int chatRow = 0;
        chatRow = addRow(chatCard, chatRow, "主题", themeCombo);
        chatRow = addRow(chatCard, chatRow, "正文字号", fontSizeSpinner);
        addRow(chatCard, chatRow, "关闭按钮", trayCheckBox);
        // 系统提示词是每套配置方案自己的值（落 Profile），随方案在模型服务页编辑
        groups.add(chatCard);
        groups.add(Box.createVerticalGlue()); // 吸掉多余高度，避免卡片被拉高
        return groups;
    }

    /** 模型服务页：配置方案管理 + 一套方案的全部参数（含系统提示词，随方案存取） */
    private JPanel buildProfilePage() {
        JPanel groups = new JPanel();
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        groups.setBackground(Ui.BG);
        groups.setBorder(BorderFactory.createEmptyBorder(6, 18, 4, 18));

        JPanel backendCard = Ui.card(Ui.CARD_BG);
        backendCard.setLayout(new GridBagLayout());
        int row = 0;
        row = addRow(backendCard, row, "配置方案", profileRow());
        row = addRow(backendCard, row, "后端地址", backendUrlField);
        row = addRow(backendCard, row, "Key", keyField);
        row = addRow(backendCard, row, "API 类型", apiTypeCombo);
        row = addRow(backendCard, row, "模型", modelRow());
        row = addRow(backendCard, row, "温度", temperatureSpinner);
        row = addRow(backendCard, row, "最大 Tokens", maxTokensSpinner);
        // 系统提示词占满卡片剩余高：多行文本区装在满高滚动容器里（原对话体验卡的排法平移过来）
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 14, 6, 14);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        backendCard.add(fieldLabel("系统提示词"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        JScrollPane promptScroll = new JScrollPane(systemPromptArea);
        promptScroll.setBorder(Ui.inputBorder(Ui.BORDER_STRONG));
        // JScrollPane 自己的背景会从圆角边框的内衬里露出来（浅色下是白看不出，深色下一圈白边）
        promptScroll.setBackground(Ui.INPUT_BG);
        promptScroll.getViewport().setBackground(Ui.INPUT_BG);
        Ui.styleScrollPane(promptScroll);
        backendCard.add(promptScroll, gbc);

        groups.add(backendCard);
        groups.add(Box.createVerticalGlue()); // 吸掉多余高度，避免卡片被拉高
        return groups;
    }

    /** 单页套滚动容器（原 groupsScroll 同款）：装不下出竖向滚动条、行永不挤压重排；
     *  滚动面板和视口一起设底色，透明轨道不露默认灰（踩坑 #19） */
    private JScrollPane wrapPage(JPanel page) {
        JScrollPane scroll = new JScrollPane(new TrackablePage(page));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBackground(Ui.BG);
        scroll.getViewport().setBackground(Ui.BG);
        Ui.styleScrollPane(scroll);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /**
     * 页面外层：宽度始终跟随视口（横向永不裁）。原先视图按自己的首选宽（约 532）
     * 保持横宽，视口 484 时右缘直接被裁 ~48px——字段右端、"删除"按钮被硬切；
     * 跟随后多余宽度由卡片里的 GridBag 压缩到加权列上：自动换行的文本区按视口
     * 宽重排，下拉/输入框填充可用宽，不再出现"内容画到视口外"。
     * 高度不跟随：页面超高照常出竖向滚动条（横向滚动条策略 NEVER 也不可见）。
     */
    private static final class TrackablePage extends JPanel implements Scrollable {
        private final JComponent content;

        TrackablePage(JComponent content) {
            super(new BorderLayout());
            this.content = content;
            add(content, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle rect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle rect, int orientation, int direction) {
            return rect.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false; // 高度不跟随：超高出竖向滚动条
        }
    }

    /** 切换标签页：CardLayout 换页 + 两枚胶囊重画（选中态在 paint 里现场比对 currentTab） */
    private void switchTab(String tabId) {
        if (currentTab.equals(tabId)) {
            return;
        }
        currentTab = tabId;
        pageLayout.show(pagesPanel, tabId);
        tabsPanel.repaint();
    }

    /**
     * 顶部标签页按钮：选中主题色实心胶囊+白字（悬停加深），未选中透底+次要字+悬停浅底。
     * paint 现场读 Ui 镜像配色（本对话框每次打开新建，不涉及换主题重建的路径）
     */
    private final class TabButton extends JButton {
        private final String tabId;
        private boolean hover;

        TabButton(String text, String tabId) {
            super(text);
            this.tabId = tabId;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder());
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            setFont(new Font("微软雅黑", Font.BOLD, 12));
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
            addActionListener(e -> switchTab(tabId));
            // 胶囊宽度随字长：文字宽 + 左右 24 内衬（不写死，两个页名字长度不等）
            setPreferredSize(new Dimension(getFontMetrics(getFont()).stringWidth(text) + 24, 28));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean selected = tabId.equals(currentTab);
            if (selected) {
                g2.setColor(hover ? Ui.ACCENT_PRESSED : Ui.ACCENT);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            } else if (hover) {
                g2.setColor(Ui.BUTTON_HOVER_BG);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            }
            g2.dispose();
            setForeground(selected ? Color.WHITE : Ui.TEXT_SECONDARY);
            super.paintComponent(g);
        }
    }

    public boolean isSaved() {
        return saved;
    }

    /**
     * 模型行：只读下拉 + 添加/删除。下拉选中项保存时写回 Profile.model（输入区默认模型）；
     * 添加走主题弹窗，确定后新名字入列并自动展开下拉（看得见的"加上了"，选中项不变）。
     */
    private JPanel modelRow() {
        Ui.styleComboBox(modelCombo);
        modelCombo.setPreferredSize(new Dimension(160, 30));
        modelCombo.setToolTipText("输入区默认模型");
        modelAddButton.setPreferredSize(new Dimension(52, 30));
        modelAddButton.setToolTipText("添加模型");
        modelDeleteButton.setPreferredSize(new Dimension(52, 30));
        modelDeleteButton.setToolTipText("删除选中的模型");
        modelDeleteButton.setEnabled(false);

        modelAddButton.addActionListener(e -> addModel());
        modelDeleteButton.addActionListener(e -> deleteModel());

        JPanel rowPanel = new JPanel(new BorderLayout(6, 0));
        rowPanel.setOpaque(false);
        rowPanel.add(modelCombo, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(modelAddButton);
        buttons.add(modelDeleteButton);
        rowPanel.add(buttons, BorderLayout.EAST);
        return rowPanel;
    }

    private void addModel() {
        String name = promptText("添加模型", "模型名（服务端标识）", "",
                value -> {
                    if (value.isEmpty()) {
                        return "模型名不能为空";
                    }
                    return indexOfModel(value) >= 0 ? "该模型已存在" : null;
                });
        if (name == null) {
            return;
        }
        modelCombo.addItem(name);
        modelCombo.repaint();
        // 自动展开下拉：新名字就在列表里，选中项仍保持默认模型不变
        modelCombo.showPopup();
        syncModelButtons();
    }

    private void deleteModel() {
        Object item = modelCombo.getSelectedItem();
        if (item == null || modelCombo.getItemCount() <= 1) {
            return; // 按钮置灰是常态，这里是双保险
        }
        modelCombo.removeItem(item);
        if (modelCombo.getSelectedItem() == null && modelCombo.getItemCount() > 0) {
            modelCombo.setSelectedIndex(0); // 删的是默认项时，显示落到第一个
        }
        syncModelButtons();
    }

    private int indexOfModel(String text) {
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            if (text.equals(modelCombo.getItemAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 删除按钮只在列表多于一项时可用（"至少保留一个模型"用置灰表达，比弹窗直观） */
    private void syncModelButtons() {
        modelDeleteButton.setEnabled(modelCombo.getItemCount() > 1);
    }

    /**
     * 主题化输入对话框（替代 JOptionPane 的 Metal 系统样式）：校验函数返回错误文案内联
     * 红字显示、不关窗，通过才收值；回车=确定，取消/关闭返回 null。
     */
    /** 主题化输入对话框（实现在 Ui.promptDialog，与侧栏重命名共用同一份） */
    private String promptText(String title, String message, String initial, Function<String, String> validator) {
        return Ui.promptDialog(this, title, message, initial, validator);
    }

    /** 主题化确认对话框：确定返回 true（实现在 Ui.confirmDialog，侧栏删除确认共用同一份） */
    private boolean confirmBox(String title, String message) {
        return Ui.confirmDialog(this, title, message, "确定", false);
    }

    /**
     * 方案行：下拉在上、新建/重命名/删除在下（两行式）。
     * 一行式（下拉 150 + 三枚按钮 198 ≈ 356）超出 GridBag 压缩后的列宽：本对话框在
     * WSLg 下窗口装饰吞掉左右各 38px，视口只有 484，卡片被压到 448、列宽 308，
     * 而 FlowLayout 永不换行（踩坑 #23）会把"删除"按钮裁掉一半。分两行后行首选宽
     * = max(下拉 150, 按钮 198) = 198，任何宽度的窗口都稳；下拉占满整行随窗口伸缩。
     */
    private JPanel profileRow() {
        JPanel rowPanel = new JPanel(new GridBagLayout());
        rowPanel.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL; // 下拉占满行宽：宽窗更易读，窄窗收缩
        rowPanel.add(profileCombo, gbc);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.setOpaque(false);
        buttons.add(profileAddButton);
        buttons.add(profileRenameButton);
        buttons.add(profileDeleteButton);
        gbc.gridy = 1;
        gbc.insets = new Insets(4, 0, 0, 0);
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST; // 按钮按自然宽度靠左，不随拉伸变宽
        rowPanel.add(buttons, gbc);

        profileCombo.addActionListener(e -> {
            if (suppressProfileSwitch) {
                return;
            }
            Object selected = profileCombo.getSelectedItem();
            if (selected instanceof String name && working.containsKey(name)) {
                selectedName = name;
                loadSelectedProfile(); // 切换即放弃未保存的修改，和常见软件一致
            }
        });
        profileAddButton.addActionListener(e -> addProfile());
        profileRenameButton.addActionListener(e -> renameProfile());
        profileDeleteButton.addActionListener(e -> deleteProfile());

        rebuildProfileCombo();
        return rowPanel;
    }

    private void rebuildProfileCombo() {
        suppressProfileSwitch = true;
        profileCombo.removeAllItems();
        for (String name : working.keySet()) {
            profileCombo.addItem(name);
        }
        profileCombo.setSelectedItem(selectedName);
        suppressProfileSwitch = false;
        profileDeleteButton.setEnabled(working.size() > 1);
    }

    /** 把选中方案的参数刷进各输入控件 */
    private void loadSelectedProfile() {
        Profile profile = working.get(selectedName);
        if (profile == null) {
            return;
        }
        backendUrlField.setText(profile.getBackendUrl());
        keyField.setText(profile.getKey());
        apiTypeCombo.setSelectedItem(profile.getApiType());
        modelCombo.removeAllItems();
        for (String model : profile.getModels()) {
            modelCombo.addItem(model);
        }
        modelCombo.setSelectedItem(profile.getModel());
        if (modelCombo.getSelectedItem() == null && modelCombo.getItemCount() > 0) {
            modelCombo.setSelectedIndex(0);
        }
        syncModelButtons();
        temperatureSpinner.setValue(profile.getTemperature());
        maxTokensSpinner.setValue(profile.getMaxTokens());
        systemPromptArea.setText(profile.getSystemPrompt());
    }

    /** 新建方案：复制当前方案参数作为起点 */
    private void addProfile() {
        String name = promptText("新建方案", "方案名称（复制当前方案参数）", suggestProfileName(),
                value -> {
                    String trimmed = value.strip();
                    if (trimmed.isEmpty()) {
                        return "名称不能为空";
                    }
                    if (trimmed.contains(",")) {
                        return "名称不能包含英文逗号";
                    }
                    return working.containsKey(trimmed) ? "方案「" + trimmed + "」已存在" : null;
                });
        if (name == null) {
            return;
        }
        working.put(name, working.get(selectedName).copy());
        selectedName = name;
        rebuildProfileCombo();
        loadSelectedProfile();
    }

    private void renameProfile() {
        String name = promptText("重命名方案", "新的方案名称", selectedName,
                value -> {
                    String trimmed = value.strip();
                    if (trimmed.isEmpty()) {
                        return "名称不能为空";
                    }
                    if (trimmed.contains(",")) {
                        return "名称不能包含英文逗号";
                    }
                    return working.containsKey(trimmed) ? "方案「" + trimmed + "」已存在" : null;
                });
        if (name == null) {
            return;
        }
        LinkedHashMap<String, Profile> reordered = new LinkedHashMap<>();
        for (Map.Entry<String, Profile> entry : working.entrySet()) {
            reordered.put(entry.getKey().equals(selectedName) ? name : entry.getKey(), entry.getValue());
        }
        working.clear();
        working.putAll(reordered);
        selectedName = name;
        rebuildProfileCombo();
    }

    private void deleteProfile() {
        if (!confirmBox("删除方案", "删除方案「" + selectedName + "」？")) {
            return;
        }
        working.remove(selectedName);
        selectedName = working.keySet().iterator().next();
        rebuildProfileCombo();
        loadSelectedProfile();
    }

    private String suggestProfileName() {
        for (int i = 2; ; i++) {
            String candidate = "方案" + i;
            if (!working.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    private void save() {
        Profile profile = working.get(selectedName);
        profile.setBackendUrl(backendUrlField.getText().strip());
        profile.setKey(keyField.getText().strip());
        profile.setApiType((String) apiTypeCombo.getSelectedItem());
        List<String> models = new ArrayList<>();
        for (int i = 0; i < modelCombo.getItemCount(); i++) {
            String item = modelCombo.getItemAt(i);
            if (item != null && !item.isBlank()) {
                models.add(item.strip());
            }
        }
        profile.setModels(models);
        String selected = (String) modelCombo.getSelectedItem();
        profile.setModel(selected != null && !selected.isBlank() ? selected.strip() : models.get(0));
        profile.setTemperature(((Number) temperatureSpinner.getValue()).doubleValue());
        profile.setMaxTokens(((Number) maxTokensSpinner.getValue()).intValue());
        profile.setSystemPrompt(systemPromptArea.getText());
        settings.setContentFontSize(((Number) fontSizeSpinner.getValue()).intValue());
        settings.setUiTheme(switch (themeCombo.getSelectedIndex()) {
            case 0 -> "system";
            case 2 -> "dark";
            default -> "light";
        });
        settings.setMinimizeToTray(trayCheckBox.isSelected());
        settings.applyProfiles(working, selectedName); // 内部落盘 + fireChanged
        saved = true;
        dispose();
    }

    private JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Ui.FONT_PLAIN);
        label.setForeground(Ui.TEXT);
        label.setPreferredSize(new Dimension(84, 20));
        return label;
    }

    private int addRow(JPanel card, int row, String label, JComponent editor) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 14, 6, 14);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        card.add(fieldLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        card.add(editor, gbc);
        return row + 1;
    }
}

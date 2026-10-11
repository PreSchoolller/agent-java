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
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 设置面板（模态对话框）：编辑 AppSettings 并持久化。
 * 按"后端接口 / 对话体验"分组，白色圆角卡片承载，与主界面同一套风格。
 * "后端接口"卡片第一行是配置方案管理（下拉切换 + 新建/重命名/删除）：面板里的一切改动
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

        JPanel groups = new JPanel();
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        groups.setBackground(Ui.BG);
        groups.setBorder(BorderFactory.createEmptyBorder(18, 18, 4, 18));

        groups.add(sectionLabel("后端接口"));
        groups.add(Box.createVerticalStrut(8));

        JPanel backendCard = Ui.card(Ui.CARD_BG);
        backendCard.setLayout(new GridBagLayout());
        int row = 0;
        row = addRow(backendCard, row, "配置方案", profileRow());
        row = addRow(backendCard, row, "后端地址", backendUrlField);
        row = addRow(backendCard, row, "Key", keyField);
        row = addRow(backendCard, row, "API 类型", apiTypeCombo);
        row = addRow(backendCard, row, "模型", modelRow());
        row = addRow(backendCard, row, "温度", temperatureSpinner);
        addRow(backendCard, row, "最大 Tokens", maxTokensSpinner);
        groups.add(backendCard);

        groups.add(Box.createVerticalStrut(16));
        groups.add(sectionLabel("对话体验"));
        groups.add(Box.createVerticalStrut(8));

        JPanel chatCard = Ui.card(Ui.CARD_BG);
        chatCard.setLayout(new GridBagLayout());
        int chatRow = 0;
        chatRow = addRow(chatCard, chatRow, "主题", themeCombo);
        chatRow = addRow(chatCard, chatRow, "正文字号", fontSizeSpinner);
        chatRow = addRow(chatCard, chatRow, "关闭按钮", trayCheckBox);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 14, 6, 14);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = chatRow;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        chatCard.add(fieldLabel("系统提示词"), gbc);
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
        chatCard.add(promptScroll, gbc);
        groups.add(chatCard);
        groups.add(Box.createVerticalGlue()); // 吸掉多余高度，避免卡片被拉高

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

        // 内容套滚动容器：装得下就全展示，装不下出竖向滚动条——GridBag 永远不缺高度，
        // 任何行都不会被挤压重排（主题下拉被拉高、正文字号被压扁的根源），以后再加行
        // 内容变长自动滚动。滚动面板和视口一起设底色，透明轨道不露默认灰（踩坑 #19）
        JScrollPane groupsScroll = new JScrollPane(groups);
        groupsScroll.setBorder(BorderFactory.createEmptyBorder());
        groupsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        groupsScroll.setBackground(Ui.BG);
        groupsScroll.getViewport().setBackground(Ui.BG);
        Ui.styleScrollPane(groupsScroll);
        groupsScroll.getVerticalScrollBar().setUnitIncrement(16);
        root.add(groupsScroll, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);

        // 高度跟随内容首选：pack 拿全量（保底 700），小屏按屏高九成截断——超出部分由上面的
        // 滚动容器接管，行高永远按首选渲染
        pack();
        int screenHeight = Toolkit.getDefaultToolkit().getScreenSize().height;
        setSize(560, Math.min(Math.max(getHeight(), 700), screenHeight * 9 / 10));
        setLocationRelativeTo(owner);
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

    /** 方案行：下拉 + 新建/重命名/删除 */
    private JPanel profileRow() {        JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rowPanel.setOpaque(false);
        rowPanel.add(profileCombo);
        rowPanel.add(profileAddButton);
        rowPanel.add(profileRenameButton);
        rowPanel.add(profileDeleteButton);

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

    private JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("微软雅黑", Font.BOLD, 13));
        label.setForeground(Ui.TEXT);
        label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        return label;
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

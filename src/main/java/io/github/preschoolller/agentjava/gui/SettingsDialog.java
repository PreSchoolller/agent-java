package io.github.preschoolller.agentjava.gui;

import io.github.preschoolller.agentjava.store.AppSettings;
import io.github.preschoolller.agentjava.store.Profile;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
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
import java.util.LinkedHashMap;
import java.util.Map;

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
    private final JButton profileAddButton = Ui.button("新建");
    private final JButton profileRenameButton = Ui.button("重命名");
    private final JButton profileDeleteButton = Ui.button("删除");
    private final JTextField backendUrlField = new JTextField();
    private final JTextField modelField = new JTextField();
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

        Ui.styleInput(backendUrlField);
        Ui.styleInput(modelField);
        Ui.styleSpinner(temperatureSpinner);
        Ui.styleSpinner(maxTokensSpinner);
        Ui.styleSpinner(fontSizeSpinner);
        Ui.styleComboBox(profileCombo);
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
        row = addRow(backendCard, row, "模型", modelField);
        row = addRow(backendCard, row, "温度", temperatureSpinner);
        addRow(backendCard, row, "最大 Tokens", maxTokensSpinner);
        groups.add(backendCard);

        groups.add(Box.createVerticalStrut(16));
        groups.add(sectionLabel("对话体验"));
        groups.add(Box.createVerticalStrut(8));

        JPanel chatCard = Ui.card(Ui.CARD_BG);
        chatCard.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 14, 6, 14);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        chatCard.add(fieldLabel("正文字号"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        chatCard.add(fontSizeSpinner, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        chatCard.add(fieldLabel("系统提示词"), gbc);
        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        JScrollPane promptScroll = new JScrollPane(systemPromptArea);
        promptScroll.setBorder(Ui.inputBorder(Ui.BORDER_STRONG));
        promptScroll.getViewport().setBackground(java.awt.Color.WHITE);
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

        root.add(groups, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        setContentPane(root);

        setSize(560, 660);
        setLocationRelativeTo(owner);
    }

    public boolean isSaved() {
        return saved;
    }

    /** 方案行：下拉 + 新建/重命名/删除 */
    private JPanel profileRow() {
        JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
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
        modelField.setText(profile.getModel());
        temperatureSpinner.setValue(profile.getTemperature());
        maxTokensSpinner.setValue(profile.getMaxTokens());
        systemPromptArea.setText(profile.getSystemPrompt());
    }

    /** 新建方案：复制当前方案参数作为起点 */
    private void addProfile() {
        String name = askProfileName("新建方案（复制当前方案参数）", suggestProfileName());
        if (name == null) {
            return;
        }
        if (working.containsKey(name)) {
            JOptionPane.showMessageDialog(this, "方案「" + name + "」已存在", "新建方案", JOptionPane.WARNING_MESSAGE);
            return;
        }
        working.put(name, working.get(selectedName).copy());
        selectedName = name;
        rebuildProfileCombo();
        loadSelectedProfile();
    }

    private void renameProfile() {
        String name = askProfileName("重命名方案", selectedName);
        if (name == null) {
            return;
        }
        if (working.containsKey(name)) {
            JOptionPane.showMessageDialog(this, "方案「" + name + "」已存在", "重命名方案", JOptionPane.WARNING_MESSAGE);
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
        int choice = JOptionPane.showConfirmDialog(this,
                "删除方案「" + selectedName + "」？", "删除方案",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        working.remove(selectedName);
        selectedName = working.keySet().iterator().next();
        rebuildProfileCombo();
        loadSelectedProfile();
    }

    /** 弹输入框要一个方案名；取消或空白返回 null（逗号是落盘时方案名的分隔符，不许用） */
    private String askProfileName(String message, String initial) {
        String name = JOptionPane.showInputDialog(this, message, initial);
        if (name == null) {
            return null;
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            JOptionPane.showMessageDialog(this, "名称不能为空", "方案名称", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        if (trimmed.contains(",")) {
            JOptionPane.showMessageDialog(this, "名称不能包含英文逗号", "方案名称", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return trimmed;
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
        profile.setModel(modelField.getText().strip());
        profile.setTemperature(((Number) temperatureSpinner.getValue()).doubleValue());
        profile.setMaxTokens(((Number) maxTokensSpinner.getValue()).intValue());
        profile.setSystemPrompt(systemPromptArea.getText());
        settings.setContentFontSize(((Number) fontSizeSpinner.getValue()).intValue());
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

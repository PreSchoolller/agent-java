package io.github.preschoolller.agentjava.gui;

import io.github.preschoolller.agentjava.store.AppSettings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
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

/**
 * 设置面板（模态对话框）：编辑 AppSettings 并持久化。
 * 按"后端接口 / 对话体验"分组，白色圆角卡片承载，与主界面同一套风格。
 * 保存后 fireChanged，后端可以在 AppSettings 的监听里感知配置变化；
 * 主窗口通过 isSaved() 判断是否需要按新设置（如正文字号）重建界面。
 */
public class SettingsDialog extends JDialog {

    private final AppSettings settings = AppSettings.get();
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
        temperatureSpinner = new JSpinner(new SpinnerNumberModel(settings.getTemperature(), 0.0, 2.0, 0.1));
        maxTokensSpinner = new JSpinner(new SpinnerNumberModel(settings.getMaxTokens(), 64, 1048576, 64));
        fontSizeSpinner = new JSpinner(new SpinnerNumberModel(settings.getContentFontSize(), 12, 22, 1));
        // token 数去掉千分位逗号：换用无分组 pattern 的 editor（直接改 format 不会刷新已渲染文本）
        maxTokensSpinner.setEditor(new JSpinner.NumberEditor(maxTokensSpinner, "#"));

        backendUrlField.setText(settings.getBackendUrl());
        backendUrlField.setToolTipText("你的 agent 后端接口地址");
        modelField.setText(settings.getModel());
        systemPromptArea.setText(settings.getSystemPrompt());
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        systemPromptArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        systemPromptArea.setFont(new Font("微软雅黑", Font.PLAIN, 13));

        Ui.styleInput(backendUrlField);
        Ui.styleInput(modelField);
        Ui.styleSpinner(temperatureSpinner);
        Ui.styleSpinner(maxTokensSpinner);
        Ui.styleSpinner(fontSizeSpinner);

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

        setSize(560, 620);
        setLocationRelativeTo(owner);
    }

    public boolean isSaved() {
        return saved;
    }

    private void save() {
        settings.setBackendUrl(backendUrlField.getText().strip());
        settings.setModel(modelField.getText().strip());
        settings.setTemperature(((Number) temperatureSpinner.getValue()).doubleValue());
        settings.setMaxTokens(((Number) maxTokensSpinner.getValue()).intValue());
        settings.setContentFontSize(((Number) fontSizeSpinner.getValue()).intValue());
        settings.setSystemPrompt(systemPromptArea.getText());
        settings.save();
        settings.fireChanged();
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

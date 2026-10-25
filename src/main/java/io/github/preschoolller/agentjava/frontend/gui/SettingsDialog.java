package io.github.preschoolller.agentjava.frontend.gui;

import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import io.github.preschoolller.agentjava.frontend.store.ModelMeta;
import io.github.preschoolller.agentjava.frontend.store.Profile;
import io.github.preschoolller.agentjava.frontend.store.ToolCatalog;

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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 设置面板（模态对话框）：编辑 AppSettings 并持久化。
 * 顶部标签页分三页（胶囊切页，当前页主题色实心）：
 * 「通用」= 全局对话体验（主题/正文字号/关闭按钮/审核通知）；
 * 「模型服务」= 配置方案的全部参数（方案管理/后端地址/Key/API 类型/默认模型/温度/最大 Tokens/系统提示词
 * + 模型目录：每个模型一条可展开行，编辑名字/上下文窗口/最大输出/默认推理等级/输入能力，落 Profile.modelsMeta）；
 * 「工具」= ToolManager 注册表的勾选名单（落 AppSettings.enabledTools，后端经 getEnabledTools 消费）
 * + bash 转文件正则（落 AppSettings.bashFilePattern：命中即写临时文件执行，替代 base64 管道）。
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
    /** 审核通知三开关（全局 UI 偏好，默认全开）：工具审批到来时的提醒通道 */
    private final JCheckBox approvalSoundCheckBox = new JCheckBox("审核到来时播放系统提示音");
    private final JCheckBox approvalSystemNotifyCheckBox = new JCheckBox("审核到来时弹系统通知（托盘气泡）");
    private final JCheckBox approvalBringToFrontCheckBox = new JCheckBox("审核到来时窗口抢到前台");
    /** 日志等级下拉：TinyLog 重启生效，选项=AppSettings.allowLevels 同名值 */
    private final JComboBox<String> logLevelCombo =
            new JComboBox<>(new String[]{"trace", "debug", "info", "warn", "error"});
    private final JButton profileAddButton = Ui.button("新建");
    private final JButton profileRenameButton = Ui.button("重命名");
    private final JButton profileDeleteButton = Ui.button("删除");
    private final JTextField backendUrlField = new JTextField();
    private final JTextField keyField = new JTextField();
    /** 后端 API 类型（协议分派由后端实现，当前仅 response 生效） */
    private final JComboBox<String> apiTypeCombo = new JComboBox<>(Profile.API_TYPES.toArray(new String[0]));
    /** 默认模型下拉（选中项=输入区默认模型）；选项由模型目录条目名填充 */
    private final JComboBox<String> modelCombo = new JComboBox<>();
    private final JButton modelAddButton = Ui.button("添加");
    /** 模型目录工作副本：条目 UI 直接编辑这里的元数据，保存时整体写回方案（面板公共约定） */
    private List<ModelMeta> modelCatalog = new ArrayList<>();
    private JPanel catalogPanel;
    /** 每个条目的展开态（按元数据对象跟踪；目录重建不丢状态，换方案清空重来） */
    private final Map<ModelMeta, Boolean> expandedStates = new IdentityHashMap<>();
    /** 工具页的勾选框（按注册表顺序建；保存时按 clientProperty 里的工具名收集勾选结果） */
    private final List<JCheckBox> toolCheckBoxes = new ArrayList<>();
    /** bash 转文件正则（工具页）：命令文本命中即改写临时文件执行，替代 base64 管道；
     *  非空时必须是合法且安全的正则（AppSettings.isValidBashFilePattern），保存前拦截 */
    private final JTextField bashPatternField = new JTextField();
    /** 正则行的内联提示：默认解释转文件行为，校验失败换红色错误文案 */
    private JLabel bashPatternHint;
    /** 当前行删除按钮（随目录重建刷新；Shot 断言初始可用态用） */
    private final List<JButton> rowDeleteButtons = new ArrayList<>();
    private final JSpinner temperatureSpinner;
    private final JSpinner maxTokensSpinner;
    private final JSpinner fontSizeSpinner;
    private final JTextArea systemPromptArea = new JTextArea(6, 24);
    private boolean saved;

    /** 标签页 id：通用（全局对话体验）/ 模型服务（配置方案参数）/ 工具（注册表勾选名单）；默认页=模型服务 */
    private static final String TAB_GENERAL = "general";
    private static final String TAB_PROFILE = "profile";
    private static final String TAB_TOOLS = "tools";
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
        approvalSoundCheckBox.setSelected(settings.isApprovalSound());
        approvalSystemNotifyCheckBox.setSelected(settings.isApprovalSystemNotify());
        approvalSystemNotifyCheckBox.setToolTipText("系统不支持托盘、或从托盘注销时，这一路不生效");
        approvalBringToFrontCheckBox.setSelected(settings.isApprovalBringToFront());
        approvalBringToFrontCheckBox.setToolTipText("窗口已有焦点时不抢；最小化/托盘隐藏的会先还原到前台");
        for (JCheckBox box : new JCheckBox[]{approvalSoundCheckBox, approvalSystemNotifyCheckBox,
                approvalBringToFrontCheckBox}) {
            Ui.styleCheckBox(box);
        }
        // 日志等级：TinyLog 启动时读一次（Main→Configuration.set），改了不重启不生效——
        // 初值直接放等级名（不用中文别名），与写入值一一对应省映射
        logLevelCombo.setSelectedItem(settings.getLogLevel());
        // bash 转文件正则：等宽字体（正则里的符号多），未配置显示空串
        Ui.styleInput(bashPatternField);
        bashPatternField.setFont(new Font("Monospaced", Font.PLAIN, 13));
        bashPatternField.setText(settings.getBashFilePattern() == null ? "" : settings.getBashFilePattern());
        bashPatternField.setToolTipText("命令文本命中此正则时，bash 工具把命令写入临时文件后用 bash 执行"
                + "（替代 echo base64 | base64 -d | bash 管道，适合 heredoc 等管道不友好的写法）；留空=仅超长命令转文件");

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
        tabsPanel.add(new TabButton("工具", TAB_TOOLS));

        // 两页内容（CardLayout 切换）：每页各自套滚动容器——原先是两卡同屏共用一份
        // groupsScroll（装不下出滚动条的挤压重排修法），分页后短页不再继承长页的滚动空间。
        // 页高取两者较大者：切换标签时对话框尺寸不变，行也不被挤压重排
        pageLayout = new CardLayout();
        pagesPanel = new JPanel(pageLayout);
        pagesPanel.setOpaque(false);
        pagesPanel.add(wrapPage(buildGeneralPage()), TAB_GENERAL);
        pagesPanel.add(wrapPage(buildProfilePage()), TAB_PROFILE);
        pagesPanel.add(wrapPage(buildToolsPage()), TAB_TOOLS);
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

    /** 通用页：全局对话体验（主题/正文字号/关闭按钮托盘/日志等级） */
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
        chatRow++;
        addRow(chatCard, chatRow, "日志等级", buildLogLevelRow());
        // 系统提示词是每套配置方案自己的值（落 Profile），随方案在模型服务页编辑
        groups.add(chatCard);

        // 审核通知卡：工具审批到来时的三通道提醒（开关独立，落全局 UI 偏好不随方案走）
        JPanel approveCard = Ui.card(Ui.CARD_BG);
        approveCard.setLayout(new GridBagLayout());
        int approveRow = 0;
        approveRow = addRow(approveCard, approveRow, "提示音", approvalSoundCheckBox);
        approveRow = addRow(approveCard, approveRow, "系统通知", approvalSystemNotifyCheckBox);
        addRow(approveCard, approveRow, "窗口置顶", approvalBringToFrontCheckBox);
        groups.add(approveCard);
        groups.add(Box.createVerticalGlue()); // 吸掉多余高度，避免卡片被拉高
        return groups;
    }

    /**
     * 日志等级行：选项直接用 TinyLog 等级名（与落盘值一一对应，省中文别名映射）。
     * TinyLog 在 Main 启动时读一次（Configuration.set("level", ...)），改了不重启不生效——
     * 这条提示放 tooltip；不接线热更的原因：等级是日志管道的建型参数，运行期切换会中途
     * 改变滚动行为，调试场景也需要"整段会话同一等级"来排查。
     */
    private JComponent buildLogLevelRow() {
        Ui.styleComboBox(logLevelCombo);
        logLevelCombo.setPreferredSize(new Dimension(100, 30));
        logLevelCombo.setToolTipText("Tinylog 日志等级（改后需重启生效）");
        return logLevelCombo;
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
        row = addRow(backendCard, row, "默认模型", defaultModelRow());
        // 模型目录独立一行：条目自带展开参数区（上下文窗口/最大输出/推理等级/输入能力），
        // 目录长出竖向滚动由页级容器接管
        row = addRow(backendCard, row, "模型目录", catalogBlock());
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

    /**
     * 工具页：ToolManager 注册表逐条勾选（前端只存名单，后端经 AppSettings.getEnabledTools
     * 消费——声明给模型哪些工具由后端接线）。注册表经 ToolCatalog 反射读取（前端 store 不
     * 直接 import backend.tool，见 ToolCatalog 类注释）；拿不到时给兜底提示。
     */
    private JPanel buildToolsPage() {
        JPanel groups = new JPanel();
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        groups.setBackground(Ui.BG);
        groups.setBorder(BorderFactory.createEmptyBorder(6, 18, 4, 18));

        JPanel toolsCard = Ui.card(Ui.CARD_BG);
        toolsCard.setLayout(new GridBagLayout());
        List<ToolCatalog.ToolInfo> tools = ToolCatalog.registered();
        int row = 0;
        for (ToolCatalog.ToolInfo info : tools) {
            row = addToolRow(toolsCard, row, info);
        }
        if (tools.isEmpty()) {
            // 注册表没拿到（裸类路径回归/没有注册工具）：提示兜底，不改任何落盘语义
            JLabel hint = new JLabel("未拿到工具列表（后端工具类不可用或没有注册工具）；此前已配置的名单保持不变");
            hint.setFont(Ui.FONT_PLAIN);
            hint.setForeground(Ui.TEXT_SECONDARY);
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(10, 14, 10, 14);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.gridwidth = 2;
            toolsCard.add(hint, gbc);
        }
        groups.add(toolsCard);

        // bash 转文件正则卡：命中正则的命令写临时文件执行（替代 base64 管道）。非空时必须是
        // 合法且安全的正则——内联红字实时提示，保存时再拦一道（AppSettings setter 是最后防线）
        JPanel bashCard = Ui.card(Ui.CARD_BG);
        bashCard.setLayout(new GridBagLayout());
        addRow(bashCard, 0, "文件正则", bashPatternRow());
        groups.add(bashCard);
        groups.add(Box.createVerticalGlue()); // 吸掉多余高度，避免卡片被拉高
        return groups;
    }

    /**
     * 文件正则行：输入框 + 内联提示。提示两态——默认解释这条配置的用途（命中即转临时文件
     * 执行，替代 base64 管道），输入了非法/不安全正则时换红色错误文案。HTML 固定 330px 折行
     * （踩坑 #17 的 Swing HTML + 防踩坑 #3 的首选宽正反馈），颜色现场读 Ui 镜像。
     */
    private JComponent bashPatternRow() {
        bashPatternHint = new JLabel();
        bashPatternHint.setFont(Ui.FONT_PLAIN);
        refreshBashPatternHint();
        bashPatternField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshBashPatternHint();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshBashPatternHint();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshBashPatternHint();
            }
        });
        JPanel column = new JPanel(new BorderLayout(0, 4));
        column.setOpaque(false);
        column.add(bashPatternField, BorderLayout.NORTH);
        column.add(bashPatternHint, BorderLayout.SOUTH);
        return column;
    }

    /** 按当前输入切换内联提示：非法/不安全正则给红字，空/合法给用途说明 */
    private void refreshBashPatternHint() {
        if (bashPatternHint == null) {
            return;
        }
        String text = bashPatternField.getText();
        if (text != null && !text.isBlank() && !AppSettings.isValidBashFilePattern(text)) {
            bashPatternHint.setText("<html><div style='width:330px'><span style='color:"
                    + Ui.hex(Ui.DANGER) + "'>正则不合法或含嵌套量词（灾难性回溯风险），不能保存——请修正或清空</span></div></html>");
            return;
        }
        bashPatternHint.setText("<html><div style='width:330px'><span style='color:"
                + Ui.hex(Ui.TEXT_SECONDARY) + "'>命令文本命中此正则时，bash 工具把命令写入临时文件后用 bash 执行"
                + "（替代 echo base64 | base64 -d | bash 管道，适合 heredoc 等管道不友好的写法）；留空=仅超长命令转文件</span></div></html>");
    }

    /** 当前输入是否可保存：空串（清除配置）或合法且安全的正则 */
    private boolean bashPatternValid() {
        String text = bashPatternField.getText();
        return text == null || text.isBlank() || AppSettings.isValidBashFilePattern(text);
    }

    /** 工具一行：等宽工具名 + 勾选框（勾选框文字=说明文字）。长说明按固定 330px 宽的 HTML 分段
     *  折行（踩坑 #17 的 Swing HTML；span 颜色现场读 Ui 镜像，随主题重建生效），不进自动宽排量
     *  防布局正反馈（踩坑 #3：JLabel 首选宽=单行全文宽，会把整页撑爆/裁边） */
    private int addToolRow(JPanel card, int row, ToolCatalog.ToolInfo info) {
        JLabel name = new JLabel(info.name());
        name.setFont(new Font("Monospaced", Font.BOLD, 13));
        name.setForeground(Ui.TEXT);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 14, 6, 14);
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        card.add(name, gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        card.add(enabledToolCheckBox(info), gbc);
        return row + 1;
    }

    /** 勾选框：文字是说明文字（次要色），勾选态=AppSettings.isToolEnabled（未配置=全启用） */
    private JCheckBox enabledToolCheckBox(ToolCatalog.ToolInfo info) {
        String description = escapeHtml(info.description().replace("\n", " "));
        JCheckBox box = new JCheckBox("<html><div style='width:330px'><span style='color:"
                + Ui.hex(Ui.TEXT_SECONDARY) + "'>" + description + "</span></div></html>");
        box.setSelected(settings.isToolEnabled(info.name()));
        box.putClientProperty("toolName", info.name());
        box.setToolTipText(info.name() + "：声明给模型可选的工具");
        Ui.styleCheckBox(box);
        toolCheckBoxes.add(box);
        return box;
    }

    /** 拼进 HTML 前转义说明文字（工具描述里会有 < > & 和引号） */
    private static String escapeHtml(String raw) {
        return raw.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
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

    /** 回归取值口（Shot）：行删除按钮当前可用态——打开面板即应等于"目录条数 > 1" */
    public boolean isModelRowDeleteEnabled(int index) {
        if (index < 0 || index >= rowDeleteButtons.size()) {
            return false;
        }
        return rowDeleteButtons.get(index).isEnabled();
    }

    /** 默认模型下拉（输入区默认项，原 combo 语义不变）。选项由模型目录条目名填充 */
    private JComponent defaultModelRow() {
        Ui.styleComboBox(modelCombo);
        modelCombo.setPreferredSize(new Dimension(160, 30));
        modelCombo.setToolTipText("输入区默认模型（模型目录里的一个）");
        return modelCombo;
    }

    /** 模型目录块：竖排条目 + 末尾"添加"行；结构变化（增删/展开切换）走整体重建 */
    private JPanel catalogBlock() {
        catalogPanel = new JPanel();
        catalogPanel.setLayout(new BoxLayout(catalogPanel, BoxLayout.Y_AXIS));
        catalogPanel.setOpaque(false);
        modelAddButton.setPreferredSize(new Dimension(72, 28));
        modelAddButton.setToolTipText("添加模型（入列即展开参数区）");
        modelAddButton.addActionListener(e -> addModel());
        rebuildCatalogPanel();
        return catalogPanel;
    }

    /** 目录整体重建：条目按工作副本顺序生成；行删除钮">1 可删"只在这里算（踩坑 #25 同款：
     *  状态只留一个写者，不再有第二处会改删除钮可用态） */
    private void rebuildCatalogPanel() {
        if (catalogPanel == null) {
            // 构造期顺序：loadSelectedProfile 先于目录 UI 建（catalogBlock）——此时数据副本
            // 已就位，目录 UI 由 catalogBlock 建块时再重建一次；直接 return 避免 NPE
            return;
        }
        rowDeleteButtons.clear();
        catalogPanel.removeAll();
        for (ModelMeta meta : modelCatalog) {
            catalogPanel.add(buildModelEntryPanel(meta));
        }
        JPanel addRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        addRow.setOpaque(false);
        addRow.add(modelAddButton);
        catalogPanel.add(addRow);
        catalogPanel.revalidate();
        catalogPanel.repaint();
    }

    /** 单条模型条目：收起=名字 + 展开/删除；展开=参数区（上下文/最大输出/推理等级/输入能力） */
    private JPanel buildModelEntryPanel(ModelMeta meta) {
        boolean expanded = Boolean.TRUE.equals(expandedStates.get(meta));

        // 名字编辑：失焦提交（空名/重名回滚），提交后默认模型下拉跟随刷新
        JTextField nameField = new JTextField(meta.getName());
        Ui.styleInput(nameField);
        nameField.setPreferredSize(new Dimension(140, 28));
        nameField.setToolTipText("模型名（发给后端的 model 参数）");
        nameField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                String value = nameField.getText().strip();
                if (value.isEmpty() || value.equals(meta.getName()) || modelNameExists(value)) {
                    nameField.setText(meta.getName()); // 空名/重名回滚（重名会让默认下拉出现两个同名项）
                    return;
                }
                boolean wasDefault = oldNameMatchesDefault(meta.getName());
                meta.setName(value);
                refreshDefaultModelCombo(wasDefault ? value : (String) modelCombo.getSelectedItem());
            }
        });

        JButton expandButton = Ui.button(expanded ? "收起" : "展开");
        expandButton.setPreferredSize(new Dimension(56, 24));
        expandButton.setToolTipText(expanded ? "收起参数区" : "展开参数区");
        expandButton.addActionListener(e -> {
            expandedStates.put(meta, !expanded);
            rebuildCatalogPanel();
        });

        JButton deleteButton = Ui.button("删除");
        deleteButton.setPreferredSize(new Dimension(56, 24));
        deleteButton.setToolTipText("删除该模型");
        deleteButton.setEnabled(modelCatalog.size() > 1);
        rowDeleteButtons.add(deleteButton);
        deleteButton.addActionListener(e -> deleteModelEntry(meta));

        JPanel panel = Ui.panel(10, Ui.INPUT_BG, null);
        panel.setLayout(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(nameField, gbc);
        JPanel headerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        headerButtons.setOpaque(false);
        headerButtons.add(expandButton);
        headerButtons.add(deleteButton);
        gbc.gridx = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.EAST;
        panel.add(headerButtons, gbc);

        if (expanded) {
            // 参数区：上下文窗口/最大输出（自由文本，失焦落条目）、默认推理等级（下拉即改）、
            // 输入能力（勾选即改）；元数据即工作副本，保存时整体写回方案
            JTextField ctxField = new JTextField(meta.getCtxWindow());
            Ui.styleInput(ctxField);
            ctxField.setToolTipText("上下文窗口，如 256K 或 128000；留空=未设置（存档，消费方接入后生效）");
            ctxField.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent event) {
                    meta.setCtxWindow(ctxField.getText().strip());
                }
            });
            JTextField maxOutField = new JTextField(meta.getMaxOutput());
            Ui.styleInput(maxOutField);
            maxOutField.setToolTipText("最大输出 token 数，如 32K；留空=未设置（存档，消费方接入后生效）");
            maxOutField.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent event) {
                    meta.setMaxOutput(maxOutField.getText().strip());
                }
            });
            JComboBox<String> reasoningCombo = new JComboBox<>(
                    new String[]{"跟随对话", "关闭", "低", "中", "高", "超高"});
            reasoningCombo.setSelectedIndex(reasoningIndexOf(meta.getReasoning()));
            // 主题化下拉（漏了就是 Metal 原生样式：灰边+默认箭头钮，深色下一块刺眼白）
            Ui.styleComboBox(reasoningCombo);
            reasoningCombo.setPreferredSize(new Dimension(96, 28));
            reasoningCombo.setToolTipText("选中该模型时输入区思考强度自动带出");
            reasoningCombo.addActionListener(e -> meta.setReasoning(
                    reasoningCombo.getSelectedIndex() == 0 ? "" : REASONING_NAMES[reasoningCombo.getSelectedIndex() - 1]));
            JCheckBox textBox = new JCheckBox("文本", meta.isText());
            Ui.styleCheckBox(textBox);
            textBox.addActionListener(e -> meta.setText(textBox.isSelected()));
            JCheckBox imageBox = new JCheckBox("图片", meta.isImage());
            Ui.styleCheckBox(imageBox);
            imageBox.addActionListener(e -> meta.setImage(imageBox.isSelected()));

            // 参数区独占一行（gridwidth 跨列），内部纵向叠三行各自独立 GridBag：
            // 同一个 GridBag 里排语义不同的三行时列宽取各行 max、权重跨行耦合
            //（"最大输出"输入框被勾选行撑爆只剩 1 字），各行独立预算才互不挤
            JPanel params = new JPanel();
            params.setOpaque(false);
            params.setLayout(new BoxLayout(params, BoxLayout.Y_AXIS));

            JPanel windowRow = new JPanel(new GridBagLayout());
            windowRow.setOpaque(false);
            GridBagConstraints pgbc = new GridBagConstraints();
            pgbc.insets = new Insets(3, 0, 0, 8);
            pgbc.anchor = GridBagConstraints.WEST;
            pgbc.gridx = 0;
            pgbc.gridy = 0;
            pgbc.weightx = 0;
            pgbc.fill = GridBagConstraints.NONE;
            windowRow.add(catalogLabel("上下文"), pgbc);
            pgbc.gridx = 1;
            pgbc.weightx = 1;
            pgbc.fill = GridBagConstraints.HORIZONTAL;
            windowRow.add(ctxField, pgbc);
            pgbc.gridx = 2;
            pgbc.weightx = 0;
            pgbc.fill = GridBagConstraints.NONE;
            windowRow.add(catalogLabel("最大输出"), pgbc);
            pgbc.gridx = 3;
            pgbc.weightx = 1;
            pgbc.fill = GridBagConstraints.HORIZONTAL;
            windowRow.add(maxOutField, pgbc);
            params.add(windowRow);

            JPanel reasoningRow = new JPanel(new GridBagLayout());
            reasoningRow.setOpaque(false);
            pgbc.gridx = 0;
            pgbc.weightx = 0;
            pgbc.fill = GridBagConstraints.NONE;
            reasoningRow.add(catalogLabel("推理等级"), pgbc);
            pgbc.gridx = 1;
            pgbc.insets = new Insets(3, 0, 0, 0);
            reasoningRow.add(reasoningCombo, pgbc);
            // 尾部吸重列：多余宽全给 glue，行内容靠左（否则 GridBag 把余宽平摊，三行起始 x 漂移）
            pgbc.gridx = 2;
            pgbc.weightx = 1;
            reasoningRow.add(Box.createHorizontalGlue(), pgbc);
            params.add(reasoningRow);

            JPanel capabilityRow = new JPanel(new GridBagLayout());
            capabilityRow.setOpaque(false);
            pgbc.anchor = GridBagConstraints.WEST;
            pgbc.gridx = 0;
            pgbc.weightx = 0;
            pgbc.fill = GridBagConstraints.NONE;
            // 输入能力独占一行：勾选框行（文本+图片≈188px）在窄预算下 FlowLayout 子项
            // 永不收缩会顶出右缘被裁（图片勾选框"断言在但看不见"，踩坑 #23）
            capabilityRow.add(catalogLabel("输入能力"), pgbc);
            pgbc.gridx = 1;
            capabilityRow.add(textBox, pgbc);
            pgbc.gridx = 2;
            capabilityRow.add(imageBox, pgbc);
            pgbc.gridx = 3;
            pgbc.weightx = 1;
            capabilityRow.add(Box.createHorizontalGlue(), pgbc);
            params.add(capabilityRow);

            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.gridwidth = GridBagConstraints.REMAINDER;
            gbc.weightx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(params, gbc);
        }
        return panel;
    }

    /** 目录参数区的窄标签（fieldLabel 84px 固定宽对 4 字标签太肥，参数区两列放不下） */
    private JLabel catalogLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Ui.FONT_PLAIN);
        label.setForeground(Ui.TEXT);
        label.setPreferredSize(new Dimension(56, 20));
        return label;
    }

    /** 推理等级枚举名 → 下拉序号（"跟随对话"=0）；没设/非法 → 0 */
    private int reasoningIndexOf(String reasoning) {
        for (int i = 0; i < REASONING_NAMES.length; i++) {
            if (REASONING_NAMES[i].equals(reasoning)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** ReasoningEffortEnum 枚举名与设置面板下拉一一对应（0 序号="跟随对话"=不下发推理等级） */
    private static final String[] REASONING_NAMES = {"NO", "LOW", "MEDIUM", "HIGH", "ULTRA"};

    /** 旧名是否就是默认模型（重命名提交后默认下拉跟着换名） */
    private boolean oldNameMatchesDefault(String oldName) {
        return oldName.equals(modelCombo.getSelectedItem());
    }

    /** 条目名重名检查（strip 比较；空串交给空名回滚分支） */
    private boolean modelNameExists(String name) {
        String target = name.strip();
        for (ModelMeta meta : modelCatalog) {
            if (meta.getName() != null && meta.getName().strip().equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 默认模型下拉重建（唯一写者）。preferred=期望选中名；不在目录里落到第一个 */
    private void refreshDefaultModelCombo(String preferred) {
        modelCombo.removeAllItems();
        for (ModelMeta meta : modelCatalog) {
            String name = meta.getName();
            if (name != null && !name.isBlank()) {
                modelCombo.addItem(name);
            }
        }
        String target = preferred != null && modelNameExists(preferred)
                ? preferred
                : modelCatalog.isEmpty() ? null : modelCatalog.get(0).getName();
        modelCombo.setSelectedItem(target);
        if (modelCombo.getSelectedItem() == null && modelCombo.getItemCount() > 0) {
            modelCombo.setSelectedIndex(0);
        }
    }

    private void addModel() {
        String name = promptText("添加模型", "模型名（服务端标识）", "",
                value -> {
                    if (value.isEmpty()) {
                        return "模型名不能为空";
                    }
                    return modelNameExists(value) ? "该模型已存在" : null;
                });
        if (name == null) {
            return;
        }
        ModelMeta meta = new ModelMeta(name);
        modelCatalog.add(meta);
        expandedStates.put(meta, true); // 新条目直接展开参数区（看得见"加上了"，选中项不变）
        refreshDefaultModelCombo((String) modelCombo.getSelectedItem());
        rebuildCatalogPanel();
    }

    private void deleteModelEntry(ModelMeta meta) {
        if (modelCatalog.size() <= 1) {
            return; // 行删除钮置灰是常态，这里是双保险
        }
        modelCatalog.remove(meta);
        expandedStates.remove(meta);
        refreshDefaultModelCombo((String) modelCombo.getSelectedItem());
        rebuildCatalogPanel();
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
        modelCatalog = profile.getModelMetas(); // 工作副本：条目直接编辑元数据，保存时整体写回
        expandedStates.clear(); // 换方案目录从收起态开始
        rebuildCatalogPanel();  // 目录重建（行删除钮可用态只在这里算）
        refreshDefaultModelCombo(profile.getModel());
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
        // 非正常/不安全正则不入库：整次保存拦在这里（先于一切写值），人带回字段修正或清空——
        // setter 里还有一道校验兜底，但那会"静默丢值关窗"，这里拦下才算把原因说清楚
        if (!bashPatternValid()) {
            Ui.messageDialog(this, "保存受阻", "「文件正则」不是合法且安全的正则"
                    + "（编译不过或含灾难性回溯的嵌套量词），未保存任何改动。请修正或清空后再保存。");
            refreshBashPatternHint();
            bashPatternField.requestFocusInWindow();
            return;
        }
        Profile profile = working.get(selectedName);
        profile.setBackendUrl(backendUrlField.getText().strip());
        profile.setKey(keyField.getText().strip());
        profile.setApiType((String) apiTypeCombo.getSelectedItem());
        // 模型目录从条目工作副本收集（名字空白行忽略——重命名中间态兜底）；默认项不在目录里
        // 落到第一个（Profile 统一兜底），上下文/最大输出/推理等级/输入能力随元数据一起落盘
        List<ModelMeta> metas = new ArrayList<>();
        for (ModelMeta meta : modelCatalog) {
            if (meta.getName() != null && !meta.getName().isBlank()) {
                metas.add(meta.copy());
            }
        }
        profile.setModelMetas(metas);
        String selected = (String) modelCombo.getSelectedItem();
        profile.setModel(selected != null && !selected.isBlank() && profile.containsModel(selected.strip())
                ? selected.strip() : profile.getModel());
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
        settings.setApprovalSound(approvalSoundCheckBox.isSelected());
        settings.setApprovalSystemNotify(approvalSystemNotifyCheckBox.isSelected());
        settings.setApprovalBringToFront(approvalBringToFrontCheckBox.isSelected());
        // 工具勾选：全部勾选=恢复"未配置"语义（未来新注册的工具自动启用）；全部不勾=显式空名单
        // （全不启用是合法配置，别当成未配置洗掉）。注册表没拿到（反射失败）时不动这项，
        // 避免用空勾选把已有配置洗净
        if (!toolCheckBoxes.isEmpty()) {
            List<String> checked = new ArrayList<>();
            for (JCheckBox box : toolCheckBoxes) {
                if (box.isSelected()) {
                    checked.add((String) box.getClientProperty("toolName"));
                }
            }
            settings.setEnabledTools(checked.size() == toolCheckBoxes.size() ? null : checked);
        }
        settings.setLogLevel((String) logLevelCombo.getSelectedItem());
        // 空白=清除配置；setter 内还有一道合法性校验（防御其他调用方）
        settings.setBashFilePattern(bashPatternField.getText());
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

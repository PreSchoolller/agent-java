package io.github.preschoolller.agentjava.frontend.gui;

import io.github.preschoolller.agentjava.frontend.store.Conversation;
import io.github.preschoolller.agentjava.frontend.store.ConversationStore;
import io.github.preschoolller.agentjava.frontend.store.Message;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 左侧面板：顶部是汉堡按钮（折叠/展开，收起后仍留在窗口左上角）和"新对话"主按钮，
 * 下面是会话搜索框（按标题/内容过滤，命中词在列表里高亮），中间是历史会话列表
 * （置顶优先、其余按最近使用；点击切换，右键置顶/重命名/删除/导出），底部是设置入口。
 */
public class Sidebar extends JPanel {

    public interface Callbacks {
        void onToggleCollapse();

        void onNewConversation();

        /** 按工作区分组后组头的"＋"：跳过工作区弹窗直接在该工作区新建（workspace=null=默认工作区） */
        void onNewConversationInWorkspace(String workspace);

        void onSelectConversation(String conversationId);

        void onOpenSettings();
    }

    public static final int EXPANDED_WIDTH = 240;
    public static final int COLLAPSED_WIDTH = 70;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final String SEARCH_HINT = "搜索对话";

    private final ConversationStore store;
    private final Callbacks callbacks;
    /** 仓库变更监听：存字段是为了 detach 时能注销同一个实例 */
    private final Runnable refreshListener = this::refresh;
    /** 行模型混装两类行：GroupRow（工作区分组头）+ Conversation（会话行） */
    private final DefaultListModel<Object> listModel = new DefaultListModel<>();
    private final JList<Object> convList = new JList<>(listModel);
    private final JScrollPane listScroll;
    private final JButton toggleButton = Ui.button("≡");
    private final JButton newChatButton = Ui.primaryButton("＋ 新对话");
    private final JButton settingsButton = Ui.button("设置");
    private final JTextField searchField = new JTextField();
    private boolean expanded = true;
    private boolean refreshingList;
    private boolean searchPlaceholder = true;
    /** 当前生效的搜索词（小写、已去空白；占位状态下为空），渲染器高亮时用 */
    private String currentQuery = "";

    /** 侧栏行模型里的工作区分组头（非会话行）：不可选中、右键无菜单；
     *  显示行右侧的"＋"点一下直接在该工作区新建对话。workspace=null=默认工作区组（未绑定的会话） */
    public static final class GroupRow {
        public final String title;
        public final String workspace;
        public final String tooltip;

        GroupRow(String title, String workspace, String tooltip) {
            this.title = title;
            this.workspace = workspace;
            this.tooltip = tooltip;
        }
    }

    /** 组头右侧"＋"的可点击区宽度（ px，相对单元格右缘） */
    private static final int PLUS_ZONE_PX = 26;

    /** 会话条目的运行状态标注（并行 agent 循环：会话在后台生成/等审批时侧栏可见）。
     *  待确认本身就意味着这轮链还开着，渲染时优先于生成中。 */
    public enum Badge {
        /** 这轮 agent 链还在跑（一轮回复或工具收尾中） */
        GENERATING,
        /** 有工具调用正等用户点"允许/拒绝" */
        APPROVAL,
    }

    /** 状态标注查询（Window 注入）：会话 id → 标注；null = 无标注。须在 EDT 上调用与使用 */
    private Function<String, Badge> badgeLookup = id -> null;

    public void setBadgeLookup(Function<String, Badge> lookup) {
        this.badgeLookup = lookup;
    }

    public Sidebar(ConversationStore store, Callbacks callbacks) {
        this.store = store;
        this.callbacks = callbacks;

        setLayout(new BorderLayout(0, 8));
        setBackground(Ui.SIDEBAR_BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Ui.DIVIDER_LINE),
                BorderFactory.createEmptyBorder(10, 8, 10, 8)));

        initTopButtons();
        initSearchField();
        initConversationList();
        initSettingsButton();

        listScroll = new JScrollPane(convList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        // 滚动面板和视口要一起设底色：透明轨道透出的是面板自己的默认 #EEEEEE，和侧栏底色差一点
        // 色相，列表长到出滚动条时就是一道不搭的竖条（踩坑 #19，主区 waterfallScroll 两处都设了）
        listScroll.setBackground(Ui.SIDEBAR_BG);
        listScroll.getViewport().setBackground(Ui.SIDEBAR_BG);
        // 片段预览/超长标题宁可裁切也不出横向滚动条（侧栏宽度是固定的）
        listScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        Ui.styleScrollPane(listScroll);
        add(listScroll, BorderLayout.CENTER);

        store.addChangeListener(refreshListener);
        refresh();
        setExpanded(true);

        // 宽度由 JSplitPane 的分隔条控制：首选宽决定初始位置，最小/最大宽限制拖拽范围
        setPreferredSize(new Dimension(EXPANDED_WIDTH, 100));
        setMinimumSize(new Dimension(40, 0));
        setMaximumSize(new Dimension(520, Integer.MAX_VALUE));
    }

    /** 主窗口重建（换主题）时调用：注销仓库监听，旧实例随之可整体回收 */
    void detach() {
        store.removeChangeListener(refreshListener);
    }

    public boolean isExpanded() {
        return expanded;
    }

    /** 折叠/展开：收起后只剩左上角的汉堡按钮和左下角的设置按钮（宽度由分隔条负责） */
    public void setExpanded(boolean value) {
        expanded = value;
        listScroll.setVisible(value);
        newChatButton.setVisible(value);
        searchField.setVisible(value);
        settingsButton.setPreferredSize(new Dimension(value ? 220 : 36, 32));
        revalidate();
        repaint();
    }

    /** 按仓库最新状态 + 当前搜索词重建会话列表并高亮当前会话。
     *  行结构=工作区分组头+组内会话（主区的工作区显示条移除后侧栏是工作区唯一可见处）：
     *  组序按组内最近会话时间倒序（最近使用在上，与原先全局排序同精神），
     *  组内仍是置顶优先+最近在前；先按搜索词过滤再分组，命中才会带着自己所属组出现 */
    public void refresh() {
        refreshingList = true;
        currentQuery = searchPlaceholder ? "" : searchField.getText().strip().toLowerCase();
        listModel.clear();
        List<Conversation> conversations = new ArrayList<>(store.getConversations());
        Comparator<Conversation> pinnedFirst = Comparator
                .comparingInt((Conversation c) -> c.isPinned() ? 0 : 1)
                .thenComparing(Comparator.comparingLong(Conversation::getUpdatedAt).reversed());
        // 分组：键=会话绑定的工作区路径，null=默认工作区组（默认不落到会话上，跟随系统设置变化）
        Map<String, List<Conversation>> groups = new LinkedHashMap<>();
        for (Conversation conversation : conversations) {
            if (!matches(conversation, currentQuery)) {
                continue;
            }
            groups.computeIfAbsent(groupKey(conversation), key -> new ArrayList<>()).add(conversation);
        }
        // 组序=组内最近一次会话更新时间倒序；组内各自排成置顶优先+最近在前
        List<Map.Entry<String, List<Conversation>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort(Comparator.comparingLong(
                (Map.Entry<String, List<Conversation>> entry) -> entry.getValue().stream()
                        .mapToLong(Conversation::getUpdatedAt).max().orElse(Long.MIN_VALUE)).reversed());
        for (Map.Entry<String, List<Conversation>> entry : ordered) {
            String workspace = entry.getKey();
            listModel.addElement(new GroupRow(groupTitle(workspace), workspace, groupTooltip(workspace)));
            entry.getValue().sort(pinnedFirst);
            for (Conversation conversation : entry.getValue()) {
                listModel.addElement(conversation);
            }
        }
        Conversation active = store.getActive();
        if (matches(active, currentQuery)) {
            convList.setSelectedValue(active, true);
        } else {
            convList.clearSelection(); // 过滤时当前会话不在结果里就不高亮，也不切换
        }
        refreshCollapsedBadge(conversations);
        refreshingList = false;
    }

    /** 分组键：绑定的工作区路径；null/空=默认工作区（同一组） */
    private static String groupKey(Conversation conversation) {
        String workspace = conversation.getWorkspace();
        return workspace == null || workspace.isBlank() ? null : workspace;
    }

    /** 组头显示名：目录的最后一段（两个不同上层的同名目录会同显示名，靠 tooltip 全路径区分） */
    private static String groupTitle(String workspace) {
        if (workspace == null) {
            return "默认工作区";
        }
        java.nio.file.Path name = java.nio.file.Path.of(workspace).getFileName();
        return name == null ? workspace : name.toString();
    }

    private static String groupTooltip(String workspace) {
        return workspace == null ? "未绑定工作区的会话（跟随默认工作区设置）" : workspace;
    }

    /** 仅供回归工具：跳过加号命中区直接触发"在该工作区新建"（与组头加号同一代码路径） */
    public void newConversationInGroup(String workspace) {
        callbacks.onNewConversationInWorkspace(workspace);
    }

    /** 侧栏折叠时汉堡按钮上的角标：有会话在生成/待审批时点个色点提示（待确认优先）。
     *  展开态条目上本来就有彩色标签，角标主要供折叠态看见 */
    private void refreshCollapsedBadge(List<Conversation> conversations) {
        Badge worst = null;
        for (Conversation conversation : conversations) {
            Badge badge = badgeLookup.apply(conversation.getId());
            if (badge == Badge.APPROVAL) {
                worst = Badge.APPROVAL;
                break; // 最紧急，直接定
            }
            if (badge == Badge.GENERATING) {
                worst = Badge.GENERATING;
            }
        }
        Color dot = null;
        if (worst == Badge.APPROVAL) {
            dot = Ui.WARNING;
        } else if (worst == Badge.GENERATING) {
            dot = Ui.ACCENT;
        }
        toggleButton.putClientProperty("badgeDot", dot);
        toggleButton.repaint();
    }

    /**
     * 以"已聚焦"状态设置搜索词并刷新（等价于聚焦搜索框后手动输入）。
     * 给回归工具用，真实交互仍走输入框自己的焦点/文档监听。
     */
    public void setSearchQuery(String query) {
        if (searchPlaceholder) {
            searchPlaceholder = false;
            searchField.setText("");
            searchField.setForeground(Ui.TEXT);
        }
        searchField.setText(query);
    }

    /** 标题或任意消息内容包含关键词即命中 */
    private boolean matches(Conversation conversation, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (conversation.getTitle() != null && conversation.getTitle().toLowerCase().contains(query)) {
            return true;
        }
        for (Message message : conversation.getMessages()) {
            if (message.text().toLowerCase().contains(query)) {
                return true;
            }
        }
        return false;
    }

    private void initTopButtons() {
        toggleButton.setFont(new Font("微软雅黑", Font.BOLD, 14));
        toggleButton.setPreferredSize(new Dimension(36, 32));
        toggleButton.setToolTipText("收起/展开侧栏");
        toggleButton.addActionListener(e -> callbacks.onToggleCollapse());

        newChatButton.setPreferredSize(new Dimension(120, 32));
        newChatButton.setToolTipText("开始一段新对话（可选择工作区）");
        newChatButton.addActionListener(e -> callbacks.onNewConversation());

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttonRow.setBackground(Ui.SIDEBAR_BG);
        buttonRow.add(toggleButton);
        buttonRow.add(newChatButton);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBackground(Ui.SIDEBAR_BG);
        top.add(buttonRow);
        top.add(Box.createVerticalStrut(8));
        top.add(searchField);
        add(top, BorderLayout.NORTH);
    }

    /** 搜索框：灰字占位提示，输入即过滤会话列表 */
    private void initSearchField() {
        Ui.styleInput(searchField);
        searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        enterSearchPlaceholder();
        searchField.setToolTipText("按标题或内容过滤会话");
        searchField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (searchPlaceholder) {
                    searchPlaceholder = false;
                    searchField.setText("");
                    searchField.setForeground(Ui.TEXT);
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (searchField.getText().isEmpty()) {
                    enterSearchPlaceholder();
                }
            }
        });
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshIfNotPlaceholder();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshIfNotPlaceholder();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshIfNotPlaceholder();
            }

            private void refreshIfNotPlaceholder() {
                if (!searchPlaceholder) {
                    refresh();
                }
            }
        });
    }

    private void enterSearchPlaceholder() {
        searchPlaceholder = true;
        searchField.setText(SEARCH_HINT);
        searchField.setForeground(Ui.TEXT_SECONDARY);
    }

    private void initConversationList() {
        convList.setCellRenderer(new ConversationRenderer());
        convList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        convList.setBackground(Ui.SIDEBAR_BG);
        convList.addListSelectionListener(e -> {
            if (refreshingList || e.getValueIsAdjusting()) {
                return;
            }
            Object selected = convList.getSelectedValue();
            if (selected instanceof GroupRow) {
                // 组头不可选：点到组头（非"＋"区）把选中拉回当前会话，保持"高亮=正在看的对话"
                // 不切 store（switchTo 同 id 也会落盘重建，纯视觉行为不该惊动仓库）
                Conversation active = store.getActive();
                if (active != null && matches(active, currentQuery)) {
                    convList.setSelectedValue(active, true);
                } else {
                    convList.clearSelection();
                }
            } else if (selected instanceof Conversation conversation) {
                callbacks.onSelectConversation(conversation.getId());
            }
        });
        convList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showPopupIfNeeded(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showPopupIfNeeded(e);
                clickGroupPlus(e); // 左键松开才算点下"＋"（与系统双击/拖拽各不相干）
            }

            /** 组头右缘的"＋"命中区：点一下直接在该工作区新建对话（跳过工作区弹窗） */
            private void clickGroupPlus(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger()) {
                    return;
                }
                int index = convList.locationToIndex(e.getPoint());
                if (index < 0 || !(convList.getModel().getElementAt(index) instanceof GroupRow group)) {
                    return;
                }
                // 命中区跟随"可见右缘"而不是单元格宽度：列表可能被长会话行撑得比视口宽
                // （列表行溢出裁切的历史行为），按可见缘算才和画面上的"＋"对得上（与渲染对齐）
                Rectangle visible = convList.getVisibleRect();
                int visibleRight = visible.x + visible.width;
                if (e.getPoint().x >= visibleRight - PLUS_ZONE_PX) {
                    callbacks.onNewConversationInWorkspace(group.workspace);
                }
            }
        });
    }

    private void initSettingsButton() {
        settingsButton.setPreferredSize(new Dimension(220, 32));
        settingsButton.setToolTipText("打开设置");
        settingsButton.addActionListener(e -> callbacks.onOpenSettings());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bottom.setBackground(Ui.SIDEBAR_BG);
        bottom.add(settingsButton);
        add(bottom, BorderLayout.SOUTH);
    }

    private void showPopupIfNeeded(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int index = convList.locationToIndex(e.getPoint());
        if (index < 0) {
            return;
        }
        if (!(convList.getModel().getElementAt(index) instanceof Conversation conversation)) {
            return; // 组头行没有会话菜单（置顶/重命名/导出/删除都针对会话）
        }
        convList.setSelectedIndex(index);

        JMenuItem pinItem = Ui.menuItem(conversation.isPinned() ? "取消置顶" : "置顶");
        pinItem.addActionListener(ev -> store.setPinned(conversation.getId(), !conversation.isPinned()));
        JMenuItem renameItem = Ui.menuItem("重命名");
        renameItem.addActionListener(ev -> {
            String title = Ui.promptDialog(this, "重命名", "新的对话标题", conversation.getTitle(), null);
            if (title != null && !title.isBlank()) {
                store.rename(conversation.getId(), title);
            }
        });
        JMenuItem exportItem = Ui.menuItem("导出 Markdown");
        exportItem.addActionListener(ev -> exportMarkdown(conversation));
        JMenuItem deleteItem = Ui.menuItem("删除", true);
        deleteItem.addActionListener(ev -> {
            if (Ui.confirmDialog(this, "删除",
                    "删除对话「" + conversation.getTitle() + "」？", "删除", true)) {
                store.remove(conversation.getId());
            }
        });
        JPopupMenu popup = new JPopupMenu();
        Ui.stylePopupMenu(popup);
        popup.add(pinItem);
        popup.add(renameItem);
        popup.add(exportItem);
        popup.add(deleteItem);
        popup.show(convList, e.getX(), e.getY());
    }

    /** 把整段对话导出为 Markdown 文件（用 AWT FileDialog 弹原生系统保存框，替代 Swing 仿制的 JFileChooser） */
    private void exportMarkdown(Conversation conversation) {
        Frame owner = (Frame) SwingUtilities.windowForComponent(this);
        FileDialog dialog = new FileDialog(owner, "导出对话为 Markdown", FileDialog.SAVE);
        dialog.setFile(safeFileName(conversation.getTitle()) + ".md");
        dialog.setFilenameFilter((dir, name) -> name.toLowerCase().endsWith(".md"));
        dialog.setVisible(true);
        String fileName = dialog.getFile();
        if (fileName == null) {
            return; // 用户取消
        }
        File file = new File(dialog.getDirectory(), fileName);
        StringBuilder md = new StringBuilder("# ").append(conversation.getTitle()).append("\n\n");
        for (Message message : conversation.getMessages()) {
            String speaker = Conversation.ROLE_USER.equals(message.role()) ? "我" : "AI";
            md.append("**").append(speaker).append("：**").append("\n\n")
                    .append(message.text()).append("\n\n---\n\n");
        }
        try {
            Files.writeString(file.toPath(), md.toString());
        } catch (IOException ex) {
            Ui.messageDialog(this, "导出", "导出失败: " + ex.getMessage());
        }
    }

    private static String safeFileName(String title) {
        String cleaned = (title == null || title.isBlank() ? "新对话" : title)
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    /** 行渲染器分两路：会话行=标题一行（命中词高亮）+ 置顶标签与更新时间小字 + 内容命中片段（可选）；
     *  组头行=显示名居左 + 主题色"＋"贴右缘（可点击区）。"＋"的摆位吃过两版亏：GridBag 面板
     *  在 CellRendererPane 上靠异步 revalidate 摆位（换主题重建后"＋"整块不画，实测抓过），
     *  HTML 表格 width=100% 又不铺开（float/right-align 均无效）——最后由表头单元自己在
     *  paintComponent 里同步摆子组件，布局不再依赖面板所处的容器时机。 */
    private static final class GroupHeaderCell extends JPanel {
        final JLabel name = new JLabel();
        final JLabel plus = new JLabel("＋");
        static final int H_PAD = 6;

        GroupHeaderCell() {
            super(null);
            setOpaque(false);
            name.setFont(new Font("微软雅黑", Font.BOLD, 12));
            plus.setFont(new Font("微软雅黑", Font.BOLD, 13));
            add(name);
            add(plus);
        }

        @Override
        protected void paintComponent(Graphics g) {
            // 摆位以"本次 paint 实际可见宽"为准，不信组件自身 bounds：JList 会话行的首选宽
            // 会把列表撑得比视口宽（长标题溢出），那时 cell 的 bounds 记录的是列表宽、clip 才
            // 是真可见区——按 bounds 摆"＋"会被视口右缘整块裁掉（实测抓过：clip 223 vs 组件 244）
            Rectangle clip = g.getClipBounds();
            int width = clip == null ? getWidth() : Math.min(getWidth(), clip.x + clip.width);
            int height = getHeight();
            FontMetrics plusMetrics = plus.getFontMetrics(plus.getFont());
            int plusW = plusMetrics.stringWidth("＋");
            // "＋"贴可见区右缘（留 4px 呼吸位，与可点击区右缘对齐）；名字占余下宽度，超长直接裁切
            plus.setBounds(width - plusW - 4, (height - plusMetrics.getHeight()) / 2, plusW + 4, plusMetrics.getHeight());
            FontMetrics nameMetrics = name.getFontMetrics(name.getFont());
            name.setBounds(H_PAD, (height - nameMetrics.getHeight()) / 2,
                    width - plusW - 4 - H_PAD - 4, nameMetrics.getHeight() + 2);
            super.paintComponent(g);
        }

        @Override
        public Dimension getPreferredSize() {
            // 只用高度参与 JList 的行高推算；宽度由单元格本身决定（单元格宽=列表可视宽）
            FontMetrics nameMetrics = name.getFontMetrics(name.getFont());
            return new Dimension(1, Math.max(nameMetrics.getHeight() + 8, plus.getFontMetrics(plus.getFont()).getHeight() + 8));
        }
    }

    private class ConversationRenderer extends DefaultListCellRenderer {
        private final GroupHeaderCell headerCell = new GroupHeaderCell();

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            if (value instanceof GroupRow group) {
                headerCell.name.setText(group.title);
                headerCell.name.setForeground(Ui.TEXT_SECONDARY);
                headerCell.plus.setForeground(Ui.ACCENT); // 渲染时才读主题：换主题重建后颜色跟新
                headerCell.setToolTipText(group.tooltip);
                return headerCell;
            }
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            Conversation conversation = (Conversation) value;
            String title = conversation.getTitle() == null || conversation.getTitle().isBlank()
                    ? "新对话"
                    : conversation.getTitle();
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(conversation.getUpdatedAt()));
            String secondary = Ui.hex(Ui.TEXT_SECONDARY);
            StringBuilder html = new StringBuilder("<html><body style='padding:2px 0'>");
            html.append(highlightHtml(title, currentQuery));
            html.append("<br><small><font color='").append(secondary).append("'>");
            // 状态标注与"置顶"同款式（彩色小字标签）；待确认比生成中更该被看见，优先显示
            Badge badge = badgeLookup.apply(conversation.getId());
            if (badge != null) {
                html.append("<font color='").append(Ui.hex(badge == Badge.APPROVAL ? Ui.WARNING : Ui.ACCENT))
                        .append("'>").append(badge == Badge.APPROVAL ? "待确认" : "生成中").append("</font> · ");
            }
            if (conversation.isPinned()) {
                html.append("<font color='").append(Ui.hex(Ui.ACCENT)).append("'>置顶</font> · ");
            }
            html.append(time).append("</font></small>");
            if (!currentQuery.isEmpty() && !title.toLowerCase().contains(currentQuery)) {
                String snippet = contentSnippet(conversation, currentQuery);
                if (snippet != null) {
                    html.append("<br><small><font color='").append(secondary).append("'>")
                            .append(snippet).append("</font></small>");
                }
            }
            html.append("</body></html>");
            label.setText(html.toString());
            label.setToolTipText(title);
            label.setBackground(selected ? Ui.SIDEBAR_SELECTED : Ui.SIDEBAR_BG);
            label.setForeground(Ui.TEXT);
            label.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            return label;
        }
    }

    /**
     * 高亮写法见踩坑 #17：Swing 的 HTML 不认 HTML5 的 &lt;mark&gt;，行内背景只能靠 span 的 CSS。
     * 底色取自主题（MARK_BG），浅色下是在侧栏两种底色上都够显眼的暖黄。
     */
    private static String markOpen() {
        return "<span style='background-color:" + Ui.hex(Ui.MARK_BG) + "'>";
    }

    private static final String MARK_CLOSE = "</span>";

    /** 把 [from, to) 区间文本转义成 HTML 安全文本 */
    private static String escapeHtml(String text, int from, int to) {
        StringBuilder sb = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeHtml(String text) {
        return escapeHtml(text, 0, text.length());
    }

    /** 文本中所有 query 命中片段包上高亮底色（大小写不敏感）；query 为空原样转义返回 */
    private static String highlightHtml(String text, String query) {
        if (query.isEmpty()) {
            return escapeHtml(text);
        }
        String lower = text.toLowerCase();
        StringBuilder html = new StringBuilder();
        int from = 0;
        int hit;
        while ((hit = lower.indexOf(query, from)) >= 0) {
            html.append(escapeHtml(text, from, hit))
                    .append(markOpen())
                    .append(escapeHtml(text, hit, hit + query.length()))
                    .append(MARK_CLOSE);
            from = hit + query.length();
        }
        html.append(escapeHtml(text, from, text.length()));
        return html.toString();
    }

    /** 标题未命中时取第一条命中消息的片段做预览（命中词同样高亮）；内容没命中返回 null */
    private static String contentSnippet(Conversation conversation, String query) {
        for (Message message : conversation.getMessages()) {
            String text = message.text();
            int hit = text.toLowerCase().indexOf(query);
            if (hit < 0) {
                continue;
            }
            int start = Math.max(0, hit - 6);
            int end = Math.min(text.length(), hit + query.length() + 10);
            // 换行替换成空格但长度不变，截取区间里的命中位置不受影响
            String raw = text.substring(start, end).replace('\n', ' ').replace('\r', ' ');
            String prefix = start > 0 ? "…" : "";
            String suffix = end < text.length() ? "…" : "";
            return prefix + highlightHtml(raw, query) + suffix;
        }
        return null;
    }
}

package io.github.preschoolller.agentjava.frontend.gui;

import io.github.preschoolller.agentjava.frontend.store.Conversation;
import io.github.preschoolller.agentjava.frontend.store.ConversationStore;
import io.github.preschoolller.agentjava.frontend.store.Message;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
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
import java.util.HashMap;
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
    /** 行模型混装两类行：GroupRow（工作区分组头）+ Conversation（会话行）。
     *  批量重建：refresh 全清全建有 N+1 条 ListDataEvent，每条事件都会让模型监听链
     *  （UI Handler 的选择插行 + 挂在模型上的 Accessible 监听 + repaint 调度）整体走一遍，
     *  Windows 实机实测被放大成 1~11ms/事件且代价随行序增长（展开一次 model 段 150~220ms，
     *  列表变短的收起刷新同一行代码只是上百 µs）。rebuild 期间抑制逐条事件、末尾补发一次
     *  intervalAdded——监听者只见到最终快照；clear 的移除事件照常逐条（一次一发）不吞 */
    private static final class BatchListModel<T> extends DefaultListModel<T> {
        private boolean batching;

        void beginBatch() {
            batching = true;
        }

        void endBatch() {
            batching = false; // 必须先复位再补发：事件自己也在抑制逻辑里（首版后置，事件被自己吞掉，JList 全空白）
            int size = size();
            if (size > 0) {
                fireIntervalAdded(this, 0, size - 1);
            }
        }

        @Override
        protected void fireContentsChanged(Object source, int index0, int index1) {
            if (!batching) {
                super.fireContentsChanged(source, index0, index1);
            }
        }

        @Override
        protected void fireIntervalAdded(Object source, int index0, int index1) {
            if (!batching) {
                super.fireIntervalAdded(source, index0, index1);
            }
        }

        @Override
        protected void fireIntervalRemoved(Object source, int index0, int index1) {
            if (!batching) {
                super.fireIntervalRemoved(source, index0, index1);
            }
        }
    }

    private final BatchListModel<Object> listModel = new BatchListModel<>();
    private final JList<Object> convList = new JList<>(listModel);
    private ConversationRenderer convRenderer; // 专属行标签持有者，refresh 全量 prune
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

    /** 收起的工作区分组（键=工作区路径，默认组用空串表示；仅会话内存活，不持久化）。
     *  搜索激活时收起态被忽略（refresh 里放行所有命中行，结果整组藏掉等于搜索白做） */
    private final java.util.Set<String> collapsedGroups = new java.util.LinkedHashSet<>();

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
        convRenderer.pruneCellLabels(conversations); // 会话删光后专属标签随之回收
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
        // 批量重建：逐条 addElement 的事件在慢环境实测 1~11ms/条且随行序增长，折成一发 intervalAdded
        // （组内排序统一挪到加组头之前：收起态跳过排序会漏排，反正代价只有几百 ns）
        listModel.beginBatch();
        try {
            for (Map.Entry<String, List<Conversation>> entry : ordered) {
                String workspace = entry.getKey();
                entry.getValue().sort(pinnedFirst);
                listModel.addElement(new GroupRow(groupTitle(workspace), workspace, groupTooltip(workspace)));
                // 收起的组只留组头（搜索激活时忽略收起态——结果整组藏掉等于搜索白做）
                if (!collapsedGroups.contains(collapseKey(workspace)) || !currentQuery.isEmpty()) {
                    for (Conversation conversation : entry.getValue()) {
                        listModel.addElement(conversation);
                    }
                }
            }
        } finally {
            listModel.endBatch();
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

    /** 仅供回归工具：跳过加号命中区直接触发"在该工作区新建"（与组头加号同一代码路径，
     *  含自动展开收起组的副作用） */
    public void newConversationInGroup(String workspace) {
        createInGroup(workspace);
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
        convRenderer = new ConversationRenderer();
        convList.setCellRenderer(convRenderer);
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
                clickGroupHeader(e); // 左键松开才算点下（与系统双击/拖拽各不相干）
            }

            /** 组头左键：右缘"＋"命中区=直接在该工作区新建，其余区域=收起/展开分组 */
            private void clickGroupHeader(MouseEvent e) {
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
                    createInGroup(group.workspace); // 先撫平收起态：新建的会话得看得见
                } else {
                    toggleGroupCollapsed(group.workspace);
                }
            }
        });
    }

    /** 侧栏内部统一的新建入口：组头"＋"与回归直达都走这里。先展开该组再触发新建——
     *  收起的组里冒出新会话却看不见，等于按了没反应 */
    private void createInGroup(String workspace) {
        collapsedGroups.remove(collapseKey(workspace));
        callbacks.onNewConversationInWorkspace(workspace);
    }

    /** 收起/展开一个工作区分组并刷列表（键=工作区路径，默认组用空串；不持久化） */
    public void toggleGroupCollapsed(String workspace) {
        java.util.Set<String> collapsed = collapsedGroups;
        String key = collapseKey(workspace);
        if (collapsed.contains(key)) {
            collapsed.remove(key);
        } else {
            collapsed.add(key);
        }
        refresh();
    }

    /** 收起态键：与分组键同源（null=默认组），空串让集合保持非 null 元素 */
    private static String collapseKey(String workspace) {
        return workspace == null ? "" : workspace;
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
            // 不钉死物理字体"微软雅黑"：它的回退链是空的，几何字符缺字形就成 .notdef 方框
            // （Windows 实机截图抓过：▶ 全缺 tofu、▼ 有——WSL 的 Shot 里却都正常，字体文件
            // 不是一份；踩坑 #5 后续补坑同款，字量不足）。
            // 字形回退交给逻辑字体：沿用组件默认字体派生粗细与大小，缺的跨物理字体找
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            plus.setFont(plus.getFont().deriveFont(Font.BOLD, 13f));
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

    /** 行渲染器分两路：会话行=标题一行（命中词高亮）+ 置顶标签与更新时间小字 + 内容命中片段（可选）；
     *  组头行=显示名居左 + 主题色"＋"贴可见区右缘（可点击区）。
     *  不再继承 DefaultListCellRenderer：它每行吐同一个 JLabel，JList 一动模型就全表重算行高、
     *  逐行把每行 HTML 重新 inflate 排版一遍（实测展开一个组 10~115ms、冷启动几百 ms——
     *  "展开卡顿"的大头，工件收在下方分段诊断）。改自持 SizingLabel 按 HTML 文本缓存首选尺寸：
     *  label 的首选宽本就按不换行单行计、与视口宽无关（行高只吃高度，溢出裁切是列表历史行为），
     *  缓存安全；文本一变（徽标/时间/高亮）缓存自然失效重算。 */
    private class ConversationRenderer implements ListCellRenderer<Object> {
        private final GroupHeaderCell headerCell = new GroupHeaderCell();
        /** 每个会话一个专属行标签（按身份复用）：共享标签每次全表重算都对每行换文本，
         *  而异文 setText 会触发 BasicHTML.updateRenderer 重新解析整个 HTML 文档（实测 1~4ms/行，
         *  真实数据行行文本不同=全表都付）；专属标签文本稳定，同文 setText 走属性相等短路，全表免解析 */
        private final Map<Conversation, SizingLabel> cellLabels = new java.util.IdentityHashMap<>();

        /** 丢掉已删除会话的标签，防无界增长（refresh 全量调用） */
        void pruneCellLabels(List<Conversation> alive) {
            cellLabels.keySet().retainAll(new java.util.HashSet<>(alive));
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            if (value instanceof GroupRow group) {
                // 箭头标识收起/展开（▶=收起 ▼=展开），与名字同色同粗——点击组头切换的视觉锚点
                boolean collapsed = collapsedGroups.contains(collapseKey(group.workspace));
                headerCell.name.setText((collapsed ? "▶ " : "▼ ") + group.title);
                headerCell.name.setForeground(Ui.TEXT); // 用户反馈组头不显眼：用主文本色，比次要灰重一级
                headerCell.plus.setForeground(Ui.ACCENT); // 渲染时才读主题：换主题重建后颜色跟新
                headerCell.setToolTipText(group.tooltip + (collapsed ? "（已收起，点击展开）" : "（点击收起）"));
                return headerCell;
            }
            Conversation conversation = (Conversation) value;
            SizingLabel label = cellLabels.computeIfAbsent(conversation, c -> new SizingLabel());
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

    /** 首选尺寸带缓存的标签：key=当前 HTML 文本（首选宽按不换行计与宽度无关、字体尺寸不随主题变；
     *  换主题重建侧栏后渲染器连带缓存一起换新）。缓存设 256 条上限、触顶整表清——它只为行高
     *  重算免重复排版，多存无益 */
    private static final class SizingLabel extends JLabel {
        private final Map<String, Dimension> sizeCache = new HashMap<>();

        private SizingLabel() {
            super("", SwingConstants.LEFT);
            setOpaque(true); // 原先继承的 DefaultListCellRenderer 本就 opaque，选中底色靠它画
        }

        @Override
        public Dimension getPreferredSize() {
            String key = getText();
            Dimension cached = key == null ? null : sizeCache.get(key);
            if (cached == null) {
                cached = super.getPreferredSize();
                if (sizeCache.size() > 256) {
                    sizeCache.clear();
                }
                sizeCache.put(key, cached);
            }
            return cached;
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

package io.github.preschoolller.agentjava.gui;

import io.github.preschoolller.agentjava.store.Conversation;
import io.github.preschoolller.agentjava.store.ConversationStore;
import io.github.preschoolller.agentjava.store.Message;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
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
import java.util.List;

/**
 * 左侧面板：顶部是汉堡按钮（折叠/展开，收起后仍留在窗口左上角）和"新对话"主按钮，
 * 下面是会话搜索框（按标题/内容过滤，命中词在列表里高亮），中间是历史会话列表
 * （置顶优先、其余按最近使用；点击切换，右键置顶/重命名/删除/导出），底部是设置入口。
 */
public class Sidebar extends JPanel {

    public interface Callbacks {
        void onToggleCollapse();

        void onNewConversation();

        void onSelectConversation(String conversationId);

        void onOpenSettings();
    }

    public static final int EXPANDED_WIDTH = 240;
    public static final int COLLAPSED_WIDTH = 48;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final String SEARCH_HINT = "搜索对话";

    private final ConversationStore store;
    private final Callbacks callbacks;
    private final DefaultListModel<Conversation> listModel = new DefaultListModel<>();
    private final JList<Conversation> convList = new JList<>(listModel);
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

    public Sidebar(ConversationStore store, Callbacks callbacks) {
        this.store = store;
        this.callbacks = callbacks;

        setLayout(new BorderLayout(0, 8));
        setBackground(Ui.SIDEBAR_BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0xE0E3E8)),
                BorderFactory.createEmptyBorder(10, 8, 10, 8)));

        initTopButtons();
        initSearchField();
        initConversationList();
        initSettingsButton();

        listScroll = new JScrollPane(convList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.getViewport().setBackground(Ui.SIDEBAR_BG);
        // 片段预览/超长标题宁可裁切也不出横向滚动条（侧栏宽度是固定的）
        listScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        Ui.styleScrollPane(listScroll);
        add(listScroll, BorderLayout.CENTER);

        store.addChangeListener(this::refresh);
        refresh();
        setExpanded(true);

        // 宽度由 JSplitPane 的分隔条控制：首选宽决定初始位置，最小/最大宽限制拖拽范围
        setPreferredSize(new Dimension(EXPANDED_WIDTH, 100));
        setMinimumSize(new Dimension(40, 0));
        setMaximumSize(new Dimension(520, Integer.MAX_VALUE));
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

    /** 按仓库最新状态 + 当前搜索词重建会话列表并高亮当前会话 */
    public void refresh() {
        refreshingList = true;
        currentQuery = searchPlaceholder ? "" : searchField.getText().strip().toLowerCase();
        listModel.clear();
        List<Conversation> conversations = new ArrayList<>(store.getConversations());
        // 置顶优先，其余保持"最近使用在前"
        conversations.sort(Comparator
                .comparingInt((Conversation conversation) -> conversation.isPinned() ? 0 : 1)
                .thenComparing(Comparator.comparingLong(Conversation::getUpdatedAt).reversed()));
        for (Conversation conversation : conversations) {
            if (matches(conversation, currentQuery)) {
                listModel.addElement(conversation);
            }
        }
        Conversation active = store.getActive();
        if (matches(active, currentQuery)) {
            convList.setSelectedValue(active, true);
        } else {
            convList.clearSelection(); // 过滤时当前会话不在结果里就不高亮，也不切换
        }
        refreshingList = false;
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
        newChatButton.setToolTipText("开始一段新对话");
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
            Conversation selected = convList.getSelectedValue();
            if (selected != null) {
                callbacks.onSelectConversation(selected.getId());
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
        convList.setSelectedIndex(index);
        Conversation conversation = convList.getSelectedValue();

        JMenuItem pinItem = new JMenuItem(conversation.isPinned() ? "取消置顶" : "置顶");
        pinItem.addActionListener(ev -> store.setPinned(conversation.getId(), !conversation.isPinned()));
        JMenuItem renameItem = new JMenuItem("重命名");
        renameItem.addActionListener(ev -> {
            String title = JOptionPane.showInputDialog(this, "新的对话标题", conversation.getTitle());
            if (title != null && !title.isBlank()) {
                store.rename(conversation.getId(), title);
            }
        });
        JMenuItem exportItem = new JMenuItem("导出 Markdown");
        exportItem.addActionListener(ev -> exportMarkdown(conversation));
        JMenuItem deleteItem = new JMenuItem("删除");
        deleteItem.addActionListener(ev -> {
            int choice = JOptionPane.showConfirmDialog(this,
                    "删除对话「" + conversation.getTitle() + "」？",
                    "删除", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.OK_OPTION) {
                store.remove(conversation.getId());
            }
        });
        JPopupMenu popup = new JPopupMenu();
        popup.add(pinItem);
        popup.add(renameItem);
        popup.add(exportItem);
        popup.add(deleteItem);
        popup.show(convList, e.getX(), e.getY());
    }

    /** 把整段对话导出为 Markdown 文件 */
    private void exportMarkdown(Conversation conversation) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(safeFileName(conversation.getTitle()) + ".md"));
        chooser.setDialogTitle("导出对话为 Markdown");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        StringBuilder md = new StringBuilder("# ").append(conversation.getTitle()).append("\n\n");
        for (Message message : conversation.getMessages()) {
            String speaker = Conversation.ROLE_USER.equals(message.role()) ? "我" : "AI";
            md.append("**").append(speaker).append("：**").append("\n\n")
                    .append(message.text()).append("\n\n---\n\n");
        }
        try {
            Files.writeString(file.toPath(), md.toString());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "导出失败: " + ex.getMessage(),
                    "导出", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String safeFileName(String title) {
        String cleaned = (title == null || title.isBlank() ? "新对话" : title)
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    /** 会话条目：标题一行（命中词高亮）+ 置顶标签与更新时间小字 + 内容命中片段（可选） */
    private class ConversationRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            Conversation conversation = (Conversation) value;
            String title = conversation.getTitle() == null || conversation.getTitle().isBlank()
                    ? "新对话"
                    : conversation.getTitle();
            String time = TIME_FORMAT.format(Instant.ofEpochMilli(conversation.getUpdatedAt()));
            StringBuilder html = new StringBuilder("<html><body style='padding:2px 0'>");
            html.append(highlightHtml(title, currentQuery));
            html.append("<br><small><font color='#8A919F'>");
            if (conversation.isPinned()) {
                html.append("<font color='#3D7EFF'>置顶</font> · ");
            }
            html.append(time).append("</font></small>");
            if (!currentQuery.isEmpty() && !title.toLowerCase().contains(currentQuery)) {
                String snippet = contentSnippet(conversation, currentQuery);
                if (snippet != null) {
                    html.append("<br><small><font color='#8A919F'>").append(snippet).append("</font></small>");
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
     * 底色选了在 SIDEBAR_BG/SIDEBAR_SELECTED 上都够显眼的暖黄。
     */
    private static final String MARK_OPEN = "<span style='background-color:#FFE28A'>";
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
                    .append(MARK_OPEN)
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

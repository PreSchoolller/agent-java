package io.github.preschoolller.agentjava.frontend.store;

import io.github.preschoolller.agentjava.backend.constant.Constants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

/**
 * 会话仓库：内存里只常驻会话索引（元数据）+ 消息按需加载，本地 JSON 持久化
 * （默认 ~/.agentjava/history.json，构造时可换文件）。启动只读主文件，
 * 单会话消息文件在首次触达该会话消息时才读（Conversation.getMessages 触发 loader 读一次）。
 * 列表保持"最近使用在前"，侧栏渲染时置顶会话优先（置顶只改标记不动仓库顺序）。
 * 任何变更都会通知 changeListeners（侧栏刷新、主区切换会话都靠它）。
 */
public class ConversationStore {

    /** 历史文件格式版本：v1 = {"version":1,"conversations":[...]}。以后加字段先升它再改 load */
    private static final int HISTORY_VERSION = 1;

    private final Path file;
    // 单会话文件目录（文件名即会话 id）：save() 同步活跃会话，非活跃会话的变更由 saveAndSyncSession 补刷
    private final Path sessionsDir;
    private final List<Conversation> conversations = new ArrayList<>();
    private String activeId;
    private final List<Runnable> changeListeners = new ArrayList<>();

    public ConversationStore() {
        this(Constants.APP_USER_DIR.resolve("history.json"));
    }

    public ConversationStore(Path file) {
        this.file = file;
        this.sessionsDir = file.getParent().toAbsolutePath().resolve("sessions");
        load();
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /** 注销监听（侧栏随主窗口重建换新时，旧实例用它解除订阅） */
    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void fireChanged() {
        changeListeners.forEach(Runnable::run);
    }

    public List<Conversation> getConversations() {
        return List.copyOf(conversations);
    }

    /** 当前会话；仓库为空时自动建一段空对话（不触发变更事件） */
    public Conversation getActive() {
        Conversation active = findById(activeId);
        if (active != null) {
            return active;
        }
        if (conversations.isEmpty()) {
            Conversation created = Conversation.create();
            conversations.add(created);
            activeId = created.getId();
            return created;
        }
        active = conversations.get(0);
        activeId = active.getId();
        return active;
    }

    /** 新建一段对话并设为当前；当前已是空对话时直接复用（并把工作区改成所选的），避免堆出一串空会话。
     *  workspace 为会话绑定的工作区目录（规范化绝对路径），null = 默认工作区 */
    public Conversation newConversation(String workspace) {
        Conversation current = findById(activeId);
        if (current != null && current.isKnownEmpty()) {
            if (!java.util.Objects.equals(current.getWorkspace(), workspace)) {
                current.setWorkspace(workspace);
                save();
                fireChanged();
            }
            return current;
        }
        Conversation created = Conversation.create();
        created.setWorkspace(workspace);
        conversations.add(0, created);
        activeId = created.getId();
        save();
        fireChanged();
        return created;
    }

    /** 同 newConversation(workspace)，用默认工作区 */
    public Conversation newConversation() {
        return newConversation(null);
    }

    public void switchTo(String id) {
        if (id.equals(activeId) || findById(id) == null) {
            return;
        }
        Conversation current = findById(activeId);
        if (current != null && current.isKnownEmpty()) {
            conversations.remove(current); // 离开没说过话的空会话时顺手清掉（未加载的会话不读盘，也绝不删）
            deleteSessionFile(current.getId());
        }
        activeId = id;
        save();
        fireChanged();
    }

    public void rename(String id, String title) {
        Conversation conversation = findById(id);
        if (conversation == null || title.isBlank()) {
            return;
        }
        conversation.setTitle(title.strip());
        saveAndSyncSession(conversation);
        fireChanged();
    }

    /** 置顶/取消置顶（侧栏排序时置顶优先），变更落盘并通知 */
    public void setPinned(String id, boolean pinned) {
        Conversation conversation = findById(id);
        if (conversation == null || conversation.isPinned() == pinned) {
            return;
        }
        conversation.setPinned(pinned);
        saveAndSyncSession(conversation);
        fireChanged();
    }

    public void remove(String id) {
        conversations.removeIf(conversation -> conversation.getId().equals(id));
        deleteSessionFile(id);
        if (id.equals(activeId)) {
            activeId = conversations.isEmpty() ? null : conversations.get(0).getId();
        }
        save();
        fireChanged();
    }

    /** 向指定会话追加一条消息，会话同时挪到"最近使用"首位 */
    public Message appendMessage(String id, String role, String text) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        Message message = conversation.addMessage(role, text);
        conversations.remove(conversation);
        conversations.add(0, conversation);
        save();
        fireChanged();
        return message;
    }

    /** 从指定会话的一条 AI 回复处分叉出新会话并设为当前：分叉点之前（含这条回复）的消息
     *  原样复制进新会话，从该对话状态继续，原会话不动 */
    public Conversation fork(String id, Message target) {
        Conversation source = findById(id);
        if (source == null || !Conversation.ROLE_AI.equals(target.role())) {
            return null;
        }
        List<Message> messages = source.getMessages();
        int cut = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == target) {
                cut = i;
                break;
            }
        }
        if (cut < 0) {
            return null;
        }
        Conversation forked = Conversation.fork(messages.subList(0, cut + 1), source.getTitle(),
                source.getWorkspace());
        conversations.add(0, forked);
        activeId = forked.getId();
        save();
        fireChanged();
        return forked;
    }

    /** 删除指定会话的最后一条消息（重新生成时由后端先删旧回答），返回被删消息；没有则返回 null */
    public Message removeLastMessage(String id) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            return null;
        }
        Message removed = conversation.removeLast();
        if (removed != null) {
            saveAndSyncSession(conversation);
            fireChanged();
        }
        return removed;
    }

    /** 流式输出结束后的落库：把消息文本替换为最终内容（按消息对象同一性查找），返回替换后的新记录 */
    public Message updateMessageText(String id, Message target, String text) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            return null;
        }
        Message updated = conversation.updateMessageText(target, text);
        if (updated != null) {
            saveAndSyncSession(conversation); // 流式 finish 可能落在已切走的会话上，副本也要刷
            fireChanged();
        }
        return updated;
    }

    /** 流式输出结束的落库：正文与独立思考一起写回（按消息对象同一性查找），返回替换后的新记录 */
    public Message updateMessageContent(String id, Message target, String text, String thinking) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            return null;
        }
        Message updated = conversation.updateMessageContent(target, text, thinking);
        if (updated != null) {
            saveAndSyncSession(conversation);
            fireChanged();
        }
        return updated;
    }

    /** 追加一条工具调用消息（"运行中"占位，agent 循环每次调工具时先调这个），返回消息记录 */
    public Message appendToolCall(String id, String toolName, String argsSummary, String callId) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        Message message = conversation.addToolCall(toolName, argsSummary, callId);
        conversations.remove(conversation);
        conversations.add(0, conversation);
        save();
        fireChanged();
        return message;
    }

    /** 工具执行完成后的落库：回填结果并写终态（按消息对象同一性查找），返回替换后的新记录 */
    public Message completeToolCall(String id, Message target, String result, boolean success, String arguments) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            return null;
        }
        Message updated = conversation.updateToolResult(target, result, success, arguments);
        if (updated != null) {
            saveAndSyncSession(conversation);
            fireChanged();
        }
        return updated;
    }

    private Conversation findById(String id) {
        if (id == null) {
            return null;
        }
        for (Conversation conversation : conversations) {
            if (conversation.getId().equals(id)) {
                return conversation;
            }
        }
        return null;
    }

    /** 空会话不落盘（残留单文件一并清理），避免历史里堆满"新对话"；活跃会话顺带同步到单会话文件。
     *  按需加载下这里只碰元数据：未加载的会话磁盘上就是对的，既不读也不写，绝不被空列表覆盖 */
    public synchronized void save() {
        String currentActiveId = activeId;
        List<Object> array = new ArrayList<>();
        List<Object> messages = null;
        for (Conversation conversation : conversations) {
            if (conversation.isKnownEmpty()) {
                deleteSessionFile(conversation.getId());
                continue;
            }
            Map<String, Object> map = serializeConversation(conversation);
            if (currentActiveId.equals(conversation.getId()) && conversation.isLoaded()) {
                messages = serializeMessages(conversation);
            }
            array.add(map);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", HISTORY_VERSION);
        root.put("conversations", array);
        try {
            // 临时文件写完再原子改名：写一半崩溃时旧文件完好无损
            Path tempPath = file.resolveSibling("temp-" + file.getFileName());
            Files.createDirectories(file.getParent());
            Files.writeString(tempPath, JsonKit.write(root));
            Files.move(tempPath, file, ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("保存会话历史失败: " + e.getMessage());
        }

        if (messages != null) {
            syncNewHistory(messages, currentActiveId); // 主文件先落、单会话副本后落
        }
    }

    /** 全量落盘 + 单会话文件同步：活跃会话的副本 save() 内已写，这里补刷非活跃会话的变更 */
    private void saveAndSyncSession(Conversation changed) {
        save();
        // 活跃会话 save() 内已同步；空会话的单文件由 save() 统一清理，这里再补刷只会写回空数组。
        // 未加载的会话磁盘上就是对的（rename/setPinned 只改元数据，不碰消息），补刷反而会整文件读进来重写
        if (!changed.isLoaded() || changed.getId().equals(activeId) || changed.isKnownEmpty()) {
            return;
        }
        syncNewHistory(serializeMessages(changed), changed.getId());
    }

    private Map<String, Object> serializeConversation(Conversation conversation) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", conversation.getId());
        map.put("title", conversation.getTitle());
        map.put("createdAt", conversation.getCreatedAt());
        map.put("updatedAt", conversation.getUpdatedAt());
        map.put("pinned", conversation.isPinned());
        if (conversation.getWorkspace() != null) {
            map.put("workspace", conversation.getWorkspace()); // null（默认工作区）不写键
        }
        return map;
    }

    private static List<Object> serializeMessages(Conversation conversation) {
        List<Object> messages = new ArrayList<>();
        for (Message message : conversation.getMessages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("text", message.text());
            item.put("time", message.time());
            if (message.isToolCall()) {
                item.put("name", message.toolName());
                item.put("args", message.toolArgs());
                item.put("status", message.toolStatus());
                item.put("callId", message.callId());
            } else if (message.hasThinking()) {
                item.put("thinking", message.thinking()); // 独立思考独立键落盘，没有不写键（旧格式兼容）
            }
            messages.add(item);
        }
        return messages;
    }

    private void syncNewHistory(List<Object> messages, String conversationId) {
        // 同步保存单个会话到 sessions/<会话id>，同样走临时文件 + 原子改名
        try {
            Path tempPath = sessionsDir.resolve("temp-" + conversationId);
            Path realPath = sessionsDir.resolve(conversationId);
            Files.createDirectories(sessionsDir);
            Files.writeString(tempPath, JsonKit.write(messages));
            Files.move(tempPath, realPath, ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("保存单会话历史失败: " + conversationId + " " + e.getMessage());
        }
    }

    private void deleteSessionFile(String id) {
        try {
            Files.deleteIfExists(sessionsDir.resolve(id));
        } catch (IOException e) {
            System.err.println("删除单会话历史失败，id：" + id + " " + e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Object root = JsonKit.parse(Files.readString(file));
            // v1 起从 conversations 字段取。
            // 以后升版本时在 version 分支里做字段迁移，别把用户旧历史读丢。
            List<?> items = null;
            if (root instanceof Map<?, ?> map) {
                items = (List<?>) map.get("conversations");
            }
            if (items == null) {
                return;
            }
            for (Object item : items) {
                Map<?, ?> map = (Map<?, ?>) item;
                Conversation conversation = new Conversation(
                        str(map.get("id")), str(map.get("title")),
                        num(map.get("createdAt")), num(map.get("updatedAt")));
                conversation.setPinned(Boolean.TRUE.equals(map.get("pinned")));
                Object workspace = map.get("workspace"); // 缺键（旧文件/默认工作区）= null
                if (workspace != null && !String.valueOf(workspace).isBlank()) {
                    conversation.setWorkspace(String.valueOf(workspace));
                }
                // 按需加载：启动只读元数据，消息挂回读钩子，首次触达该会话消息时才读单会话文件。
                // 消息只认单会话文件（文件名=会话 id）——旧格式内嵌消息不迁移，读到坏文件按无消息处理
                String id = conversation.getId();
                conversation.attachLoader(() -> readSessionMessages(id));
                conversations.add(conversation);
            }
            activeId = conversations.isEmpty() ? null : conversations.get(0).getId();
        } catch (Exception e) {
            System.err.println("读取会话历史失败（忽略旧文件）: " + e.getMessage());
        }
    }

    /** 读单个会话文件并转成消息列表；文件缺失或损坏按无消息处理（只在该会话消息首次被触达时调用一次） */
    private List<Message> readSessionMessages(String id) {
        List<Message> messages = new ArrayList<>();
        Path sessionFile = sessionsDir.resolve(id);
        if (!Files.exists(sessionFile)) {
            return messages; // 从没说过话的会话没有单文件，不是错误
        }
        try {
            Object raw = JsonKit.parse(Files.readString(sessionFile));
            for (Object item : (List<?>) raw) {
                Map<?, ?> messageMap = (Map<?, ?>) item;
                // 崩溃恢复（P0-5）：running 只该存在于活着的进程内存里，读盘遇到就是上次崩了/被杀的
                // 残留，翻成 interrupted（前端显示"已中断"灰态）；其空结果回传时本就被
                // buildRoleMessages 的空文本跳过，不会发给后端
                String status = str(messageMap.get("status"));
                if ("running".equals(status)) {
                    status = "interrupted";
                }
                messages.add(messageMap.containsKey("name")
                        ? new Message(str(messageMap.get("role")), str(messageMap.get("text")),
                                num(messageMap.get("time")), str(messageMap.get("name")),
                                str(messageMap.get("args")), status, str(messageMap.get("callId")))
                        : new Message(str(messageMap.get("role")), str(messageMap.get("text")),
                                num(messageMap.get("time")), null, null, null, null,
                                // 缺键（旧文件）= null，有键才读
                                messageMap.containsKey("thinking") ? str(messageMap.get("thinking")) : null));
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("读取单会话历史出错，该会话按无消息加载: " + id + " " + exception.getMessage());
            return new ArrayList<>(); // 半截文件：已解析出来的也不要用，展示空会话比展示半截历史好
        }
        return messages;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}

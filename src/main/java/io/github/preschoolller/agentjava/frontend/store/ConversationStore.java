package io.github.preschoolller.agentjava.frontend.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话仓库：内存里的会话列表 + 本地 JSON 持久化（默认 ~/.agentjava/history.json，构造时可换文件）。
 * 列表保持"最近使用在前"，侧栏渲染时置顶会话优先（置顶只改标记不动仓库顺序）。
 * 任何变更都会通知 changeListeners（侧栏刷新、主区切换会话都靠它）。
 */
public class ConversationStore {

    /** 历史文件格式版本：v0 = 顶层数组；v1 = {"version":1,"conversations":[...]}。以后加字段先升它再改 load */
    private static final int HISTORY_VERSION = 1;

    private final Path file;
    private final List<Conversation> conversations = new ArrayList<>();
    private String activeId;
    private final List<Runnable> changeListeners = new ArrayList<>();

    public ConversationStore() {
        this(Path.of(System.getProperty("user.home"), ".agentjava", "history.json"));
    }

    public ConversationStore(Path file) {
        this.file = file;
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

    /** 新建一段对话并设为当前；当前已是空对话时直接复用，避免堆出一串空会话 */
    public Conversation newConversation() {
        Conversation current = findById(activeId);
        if (current != null && current.isEmpty()) {
            return current;
        }
        Conversation created = Conversation.create();
        conversations.add(0, created);
        activeId = created.getId();
        save();
        fireChanged();
        return created;
    }

    public void switchTo(String id) {
        if (id.equals(activeId) || findById(id) == null) {
            return;
        }
        Conversation current = findById(activeId);
        if (current != null && current.isEmpty()) {
            conversations.remove(current); // 离开没说过话的空会话时顺手清掉
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
        save();
        fireChanged();
    }

    /** 置顶/取消置顶（侧栏排序时置顶优先），变更落盘并通知 */
    public void setPinned(String id, boolean pinned) {
        Conversation conversation = findById(id);
        if (conversation == null || conversation.isPinned() == pinned) {
            return;
        }
        conversation.setPinned(pinned);
        save();
        fireChanged();
    }

    public void remove(String id) {
        conversations.removeIf(conversation -> conversation.getId().equals(id));
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

    /** 删除指定会话中的一条消息（按消息对象同一性查找），删除成功返回 true */
    public boolean removeMessage(String id, Message target) {
        Conversation conversation = findById(id);
        if (conversation == null || !conversation.removeMessage(target)) {
            return false;
        }
        save();
        fireChanged();
        return true;
    }

    /** 删除指定会话的最后一条消息（重新生成时由后端先删旧回答），返回被删消息；没有则返回 null */
    public Message removeLastMessage(String id) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            return null;
        }
        Message removed = conversation.removeLast();
        if (removed != null) {
            save();
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
            save();
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

    /** 空会话不落盘，避免历史里堆满"新对话" */
    public synchronized void save() {
        List<Object> array = new ArrayList<>();
        for (Conversation conversation : conversations) {
            if (conversation.isEmpty()) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", conversation.getId());
            map.put("title", conversation.getTitle());
            map.put("createdAt", conversation.getCreatedAt());
            map.put("updatedAt", conversation.getUpdatedAt());
            map.put("pinned", conversation.isPinned());
            List<Object> messages = new ArrayList<>();
            for (Message message : conversation.getMessages()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", message.role());
                item.put("text", message.text());
                item.put("time", message.time());
                messages.add(item);
            }
            map.put("messages", messages);
            array.add(map);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", HISTORY_VERSION);
        root.put("conversations", array);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, JsonKit.write(root));
        } catch (IOException e) {
            System.err.println("保存会话历史失败: " + e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Object root = JsonKit.parse(Files.readString(file));
            // v0 旧格式顶层数组直接当会话列表读；v1 起从 conversations 字段取。
            // 以后升版本时在 version 分支里做字段迁移，别把用户旧历史读丢。
            List<?> items = null;
            if (root instanceof Map<?, ?> map) {
                items = (List<?>) map.get("conversations");
            } else if (root instanceof List<?> list) {
                items = list;
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
                for (Object raw : (List<?>) map.get("messages")) {
                    Map<?, ?> messageMap = (Map<?, ?>) raw;
                    conversation.getMessages().add(new Message(
                            str(messageMap.get("role")), str(messageMap.get("text")), num(messageMap.get("time"))));
                }
                conversations.add(conversation);
            }
            activeId = conversations.isEmpty() ? null : conversations.get(0).getId();
        } catch (Exception e) {
            System.err.println("读取会话历史失败（忽略旧文件）: " + e.getMessage());
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}

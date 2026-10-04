package io.github.preschoolller.agentjava.store;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 一段对话：标题 + 消息列表，首条用户消息会自动截取为标题。 */
public class Conversation {

    public static final String ROLE_USER = "user";
    public static final String ROLE_AI = "ai";

    private final String id;
    private String title;
    private final long createdAt;
    private long updatedAt;
    private boolean pinned;
    private final List<Message> messages = new ArrayList<>();

    public Conversation(String id, String title, long createdAt, long updatedAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Conversation create() {
        long now = System.currentTimeMillis();
        return new Conversation(UUID.randomUUID().toString(), "新对话", now, now);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /** 置顶标记：侧栏排序时置顶优先（不 touch，updatedAt 保持"最后消息时间"语义） */
    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    /** 内部装载用：返回的是实时列表，请勿在遍历时增删 */
    public List<Message> getMessages() {
        return messages;
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public void setTitle(String title) {
        this.title = title;
        touch();
    }

    public Message addMessage(String role, String text) {
        Message message = new Message(role, text, System.currentTimeMillis());
        messages.add(message);
        touch();
        if (ROLE_USER.equals(role) && messages.size() == 1) {
            title = abbreviate(text, 20);
        }
        return message;
    }

    /** 删除最后一条消息（重新生成流程：后端先删旧回答再重新请求），返回被删消息；空会话返回 null */
    public Message removeLast() {
        if (messages.isEmpty()) {
            return null;
        }
        Message removed = messages.remove(messages.size() - 1);
        touch();
        return removed;
    }

    /** 删除一条消息（按消息对象同一性查找），成功返回 true */
    public boolean removeMessage(Message target) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == target) {
                messages.remove(i);
                touch();
                return true;
            }
        }
        return false;
    }

    /** 流式输出结束后把已存在消息的文本替换为最终值（按消息对象同一性查找），返回替换后的新记录；没找到返回 null */
    public Message updateMessageText(Message target, String text) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == target) {
                Message updated = new Message(target.role(), text, target.time());
                messages.set(i, updated);
                touch();
                return updated;
            }
        }
        return null;
    }

    private void touch() {
        updatedAt = System.currentTimeMillis();
    }

    private static String abbreviate(String text, int max) {
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}

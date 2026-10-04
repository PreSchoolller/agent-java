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

    private void touch() {
        updatedAt = System.currentTimeMillis();
    }

    private static String abbreviate(String text, int max) {
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}

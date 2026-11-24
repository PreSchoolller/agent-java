package io.github.preschoolller.agentjava.frontend;

import java.util.UUID;

/**
 * 卡片渲染用的消息载体：由 {@link ViewAdapter} 从 {@code MessageView} 转出来，窗口只读它。
 *
 * <p>不持久化、不参与请求构造——会话状态在 core 侧，这里只留画一张卡需要的字段。
 * {@code role} 取 {@link #ROLE_USER} / {@link #ROLE_AI} / {@link #ROLE_TOOL}；
 * 工具卡的 {@code toolStatus} 取 {@code STATUS_*} 之一。
 */
public record Message(String role, String text, long time, String toolName, String toolArgs,
                      String toolStatus, String callId, String thinking, String messageId) {

    public static final String ROLE_USER = "user";
    public static final String ROLE_AI = "ai";
    public static final String ROLE_TOOL = "tool";

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_OK = "ok";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_BG_RUNNING = "bg-running";
    public static final String STATUS_BG_DONE = "bg-done";
    public static final String STATUS_BG_KILLED = "bg-killed";

    /** 聊天消息（用户/AI）：工具字段与思考为 null，消息号自动生成 */
    public Message(String role, String text, long time) {
        this(role, text, time, null, null, null, null, null, UUID.randomUUID().toString());
    }

    /** 工具调用消息：思考恒为 null */
    public Message(String role, String text, long time, String toolName, String toolArgs,
                   String toolStatus, String callId) {
        this(role, text, time, toolName, toolArgs, toolStatus, callId, null, UUID.randomUUID().toString());
    }

    public boolean isToolCall() {
        return ROLE_TOOL.equals(role);
    }

    /** 换参数保持其余字段（工具卡参数增量显示用） */
    public Message withArgs(String args) {
        return new Message(role, text, time, toolName, args, toolStatus, callId, thinking, messageId);
    }
}

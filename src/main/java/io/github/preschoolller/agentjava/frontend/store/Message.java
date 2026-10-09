package io.github.preschoolller.agentjava.frontend.store;

/**
 * 一条消息。role 取 Conversation.ROLE_USER / ROLE_AI / ROLE_TOOL。
 * 工具调用消息（role=tool）的 text 存工具结果，toolName/toolArgs/toolStatus 才有值；
 * 聊天消息这三个字段为 null。toolStatus：running（运行中）/ok/error。
 */
public record Message(String role, String text, long time, String toolName, String toolArgs, String toolStatus, String callId) {

    /** 聊天消息（user/ai）用这个构造，工具字段为 null */
    public Message(String role, String text, long time) {
        this(role, text, time, null, null, null, null);
    }

    public boolean isToolCall() {
        return Conversation.ROLE_TOOL.equals(role);
    }

    /** 原样复制（分叉复制前缀用） */
    Message copy() {
        return new Message(role, text, time, toolName, toolArgs, toolStatus, callId);
    }

    /** 换正文保持其余字段（流式落库用） */
    Message withText(String newText) {
        return new Message(role, newText, time, toolName, toolArgs, toolStatus, callId);
    }
}

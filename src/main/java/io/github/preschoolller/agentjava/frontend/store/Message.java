package io.github.preschoolller.agentjava.frontend.store;

/**
 * 一条消息。role 取 Conversation.ROLE_USER / ROLE_AI / ROLE_TOOL。
 * 工具调用消息（role=tool）的 text 存工具结果，toolName/toolArgs/toolStatus 才有值；
 * 聊天消息这三个字段为 null。toolStatus：running（运行中）/ok/error。
 * AI 的思考内容独立放 thinking：appendThinking 的增量只进这个字段，正文 text 里不再
 * 拼标签（前后拼接、与正文混杂、思考正文乱序传递三个问题一起消掉）。
 * 思考内容不由 Markdown 渲染器解析，渲染走独立组件 ThinkingPane。
 */
public record Message(String role, String text, long time, String toolName, String toolArgs,
                      String toolStatus, String callId, String thinking) {

    /** 聊天消息（user/ai）用这个构造，工具字段与思考为 null */
    public Message(String role, String text, long time) {
        this(role, text, time, null, null, null, null, null);
    }

    /** 工具调用消息构造：思考恒为 null（保留既有调用点的形状） */
    public Message(String role, String text, long time, String toolName, String toolArgs,
                   String toolStatus, String callId) {
        this(role, text, time, toolName, toolArgs, toolStatus, callId, null);
    }

    public boolean isToolCall() {
        return Conversation.ROLE_TOOL.equals(role);
    }

    /** 有独立思考内容（卡片才渲染思考区、落盘才写 thinking 键） */
    public boolean hasThinking() {
        return thinking != null && !thinking.isEmpty();
    }

    /** 原样复制（分叉复制前缀用） */
    Message copy() {
        return new Message(role, text, time, toolName, toolArgs, toolStatus, callId, thinking);
    }

    /** 换正文保持其余字段含 thinking（只换正文的落库用） */
    Message withText(String newText) {
        return new Message(role, newText, time, toolName, toolArgs, toolStatus, callId, thinking);
    }

    /** 正文与思考一起换（流式 finish 落库用） */
    Message withContent(String newText, String newThinking) {
        return new Message(role, newText, time, toolName, toolArgs, toolStatus, callId, newThinking);
    }
}

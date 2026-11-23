package io.github.preschoolller.agentjava.core.repository;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * 一条消息。role 取 Conversation.ROLE_USER / ROLE_AI / ROLE_TOOL / ROLE_CONTEXT_MANAGER。
 * 工具调用消息（role=tool）的 text 存工具结果，toolName/toolArgs/toolStatus 才有值；
 * 聊天消息这三个字段为 null。toolStatus：running（运行中）/ok/error。
 * AI 的思考内容独立放 thinking：appendThinking 的增量只进这个字段，正文 text 里不再
 * 拼标签（前后拼接、与正文混杂、思考正文乱序传递三个问题一起消掉）。
 * 思考内容不由 Markdown 渲染器解析，渲染走独立组件 ThinkingPane。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Message(@JsonProperty("role") String role,
                      @JsonProperty("text") String text,
                      @JsonProperty("time") long time,
                      @JsonProperty("name") String toolName,
                      @JsonProperty("args") String toolArgs,
                      @JsonProperty("status") String toolStatus,
                      @JsonProperty("callId") String callId,
                      @JsonProperty("thinking") String thinking,
                      @JsonProperty("message_id") String messageId) {

    /** 聊天消息（user/ai）用这个构造，工具字段与思考为 null */
    public Message(String role, String text, long time) {
        this(role, text, time, null, null, null, null, null, UUID.randomUUID().toString());
    }

    /** 工具调用消息构造：思考恒为 null（保留既有调用点的形状） */
    public Message(String role, String text, long time, String toolName, String toolArgs,
                   String toolStatus, String callId) {
        this(role, text, time, toolName, toolArgs, toolStatus, callId, null, UUID.randomUUID().toString());
    }

    @JsonIgnore
    public boolean isToolCall() {
        return Conversation.ROLE_TOOL.equals(role);
    }

    /** 上下文压缩水位线标记：不进请求（请求构建按它裁剪水位线之前的历史）、瀑布流/导出不渲染 */
    @JsonIgnore
    public boolean isContextMarker() {
        return Conversation.ROLE_CONTEXT_MANAGER.equals(role);
    }

    /** 有独立思考内容（卡片才渲染思考区、落盘才写 thinking 键） */
    public boolean hasThinking() {
        return thinking != null && !thinking.isEmpty();
    }

    /** 原样复制（分叉复制前缀用） */
    public Message copy() {
        return new Message(role, text, time, toolName, toolArgs, toolStatus, callId, thinking, messageId);
    }

    /** 换正文保持其余字段含 thinking（只换正文的落库用） */
    Message withText(String newText) {
        return new Message(role, newText, time, toolName, toolArgs, toolStatus, callId, thinking, messageId);
    }

    /** 正文与思考一起换（流式 finish 落库用） */
    Message withContent(String newText, String newThinking) {
        return new Message(role, newText, time, toolName, toolArgs, toolStatus, callId, newThinking, messageId);
    }

    public Message withStatus(String status) {
        return new Message(role, text, time, toolName, toolArgs, status, callId, thinking, messageId);
    }

    public Message withArgs(String args) {
        return new Message(role, text, time, toolName, args, toolStatus, callId, thinking, messageId);
    }

    public Message withMessageId(String id) {
        return new Message(role, text, time, toolName, toolArgs, toolStatus, callId, thinking, id);
    }
}

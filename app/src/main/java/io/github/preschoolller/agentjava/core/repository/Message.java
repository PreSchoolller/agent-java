package io.github.preschoolller.agentjava.core.repository;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
public record Message(@JsonProperty("role") Role role,
                      @JsonProperty("text") String text,
                      @JsonProperty("time") long time,
                      @JsonProperty("name") String toolName,
                      @JsonProperty("args") String toolArgs,
                      @JsonProperty("status") ToolStatus toolStatus,
                      @JsonProperty("callId") String callId,
                      @JsonProperty("thinking") String thinking,
                      @JsonProperty("message_id") String messageId) {

    /** 聊天消息（user/ai）用这个构造，工具字段与思考为 null */
    public Message(Role role, String text, long time) {
        this(role, text, time, null, null, null, null, null, UUID.randomUUID().toString());
    }

    /** 工具调用消息构造：思考恒为 null（保留既有调用点的形状） */
    public Message(Role role, String text, long time, String toolName, String toolArgs,
                   ToolStatus toolStatus, String callId) {
        this(role, text, time, toolName, toolArgs, toolStatus, callId, null, UUID.randomUUID().toString());
    }

    @JsonIgnore
    public boolean isToolCall() {
        return Message.Role.ROLE_TOOL.equals(role);
    }

    /** 上下文压缩水位线标记：不进请求（请求构建按它裁剪水位线之前的历史）、瀑布流/导出不渲染 */
    @JsonIgnore
    public boolean isContextMarker() {
        return Message.Role.ROLE_CONTEXT_MANAGER.equals(role);
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

    public Message withStatus(ToolStatus status) {
        return new Message(role, text, time, toolName, toolArgs, status, callId, thinking, messageId);
    }

    public Message withArgs(String args) {
        return new Message(role, text, time, toolName, args, toolStatus, callId, thinking, messageId);
    }

    public Message withMessageId(String id) {
        return new Message(role, text, time, toolName, toolArgs, toolStatus, callId, thinking, id);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public enum Role {
        ROLE_USER("user"),
        ROLE_AI("ai"),
        ROLE_TOOL("tool"),
        /** 上下文压缩水位线标记：上下文管理插件把此标记之前的历史压缩后的边界。
         *  请求构建按最后一个标记裁剪（标记本身与更早的消息不进请求），瀑布流/导出不渲染；
         *  落盘按普通消息往返（role 字符串序列化，text 恒空串） */
        ROLE_CONTEXT_MANAGER("context-manager"),
        ;

        @JsonValue
        private final String role;

        private static final  Map<String, Role> BY_ROLE = Arrays.stream(Role.values())
                .collect(Collectors.toMap(Role::getRole, Function.identity()));

        Role(String role) {
            this.role = role;
        }

        public String getRole() {
            return role;
        }

        @JsonCreator
        public static Role fromString(String role) {
            return BY_ROLE.get(role);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public enum ToolStatus {
        RUNNING("running"),
        OK("ok"),
        ERROR("error"),
        /** 工具卡状态：命令已转入后台、当次只回了提交成功（终态等后台任务完成的回调回填） */
        STATUS_BG_RUNNING("bg-running"),
        /** 工具卡状态：后台任务已完成，输出已回填进结果区 */
        STATUS_BG_DONE("bg-done"),
        /** 工具卡状态：后台任务被用户手动终止（终态；与"已中断"区分——那是异常退出残留） */
        STATUS_BG_KILLED("bg-killed"),
        INTERRUPTED("interrupted"),
        ;

        @JsonValue
        private final String status;

        private static final Map<String, ToolStatus> BY_STATUS = Arrays.stream(ToolStatus.values())
                .collect(Collectors.toUnmodifiableMap(ToolStatus::getStatus, Function.identity()));

        ToolStatus(String status) {
            this.status = status;
        }

        public String getStatus() {
            return status;
        }

        @JsonCreator
        public static ToolStatus getByStatus(String status) {
            return BY_STATUS.get(status);
        }

        @Override
        public String toString() {
            return status;
        }
    }
}

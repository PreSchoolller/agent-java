package io.github.preschoolller.agentjava.frontend;

import io.github.preschoolller.agentjava.core.enums.ToolState;
import io.github.preschoolller.agentjava.core.records.MessageView;
import io.github.preschoolller.agentjava.core.records.ToolView;
import io.github.preschoolller.agentjava.core.records.UsageView;
import io.github.preschoolller.agentjava.core.repository.Conversation;
import io.github.preschoolller.agentjava.core.repository.Message;

/**
 * 投影到渲染载体的适配：只做字段搬运，不持有状态、不写仓库。
 *
 * <p>{@link Message} 是卡片绘制用的纯数据 record（无持久化逻辑），
 * 从 {@link MessageView} 构造后只用于显示。
 */
public final class ViewAdapter {

    private ViewAdapter() {
    }

    public static Message message(MessageView view) {
        if (view == null) {
            return null;
        }
        String role = switch (view.role()) {
            case USER -> Conversation.ROLE_USER;
            case ASSISTANT -> Conversation.ROLE_AI;
            case TOOL_RESULT -> Conversation.ROLE_TOOL;
        };
        ToolView tool = view.tool();
        if (tool == null) {
            return new Message(role, view.text(), view.time(), null, null, null, null,
                    view.thinking(), view.messageId());
        }
        return new Message(role, view.text(), view.time(), tool.name(), tool.argsSummary(),
                toolStatus(tool.state()), tool.callId(), view.thinking(), view.messageId());
    }

    public static String toolStatus(ToolState state) {
        return switch (state) {
            case RUNNING -> "running";
            case OK -> "ok";
            case ERROR -> "error";
            case INTERRUPTED -> "interrupted";
            case BG_RUNNING -> "bg-running";
            case BG_DONE -> "bg-done";
            case BG_KILLED -> "bg-killed";
        };
    }

    public static String usageLabel(UsageView usage) {
        if (usage == null) {
            return "";
        }
        return String.format("↑%,d ↓%,d · %.1fs", usage.inputTokens(), usage.outputTokens(),
                usage.elapsedSeconds());
    }

    // ---------- 双枚举（core 投影 ↔ SPI 输入区） ----------

    public static io.github.preschoolller.agentjava.core.enums.ReasoningEffort coreEffort(
            io.github.preschoolller.agentjava.spi.enums.ReasoningEffortEnum effort) {
        return effort == null ? null
                : io.github.preschoolller.agentjava.core.enums.ReasoningEffort.valueOf(effort.name());
    }

    public static io.github.preschoolller.agentjava.spi.enums.ReasoningEffortEnum spiEffort(
            io.github.preschoolller.agentjava.core.enums.ReasoningEffort effort) {
        return effort == null ? null
                : io.github.preschoolller.agentjava.spi.enums.ReasoningEffortEnum.valueOf(effort.name());
    }

    public static io.github.preschoolller.agentjava.core.enums.AgentMode coreMode(
            io.github.preschoolller.agentjava.spi.enums.AgentMode mode) {
        return mode == null ? null
                : io.github.preschoolller.agentjava.core.enums.AgentMode.valueOf(mode.name());
    }

    public static io.github.preschoolller.agentjava.spi.enums.AgentMode spiMode(
            io.github.preschoolller.agentjava.core.enums.AgentMode mode) {
        return mode == null ? null
                : io.github.preschoolller.agentjava.spi.enums.AgentMode.valueOf(mode.name());
    }
}

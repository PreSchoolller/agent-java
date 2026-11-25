package io.github.preschoolller.agentjava.core.converter;

import io.github.preschoolller.agentjava.core.enums.AgentMode;
import io.github.preschoolller.agentjava.core.enums.MessageState;
import io.github.preschoolller.agentjava.core.enums.ReasoningEffort;
import io.github.preschoolller.agentjava.core.enums.Role;
import io.github.preschoolller.agentjava.core.enums.ToolState;
import io.github.preschoolller.agentjava.core.records.MessageView;
import io.github.preschoolller.agentjava.core.records.ToolView;
import io.github.preschoolller.agentjava.core.records.UsageView;
import io.github.preschoolller.agentjava.core.repository.Conversation;
import io.github.preschoolller.agentjava.core.repository.Message;
import io.github.preschoolller.agentjava.spi.enums.ReasoningEffortEnum;

public class RecordConverter {

    public static MessageView toMessageView(Message message) {
        if (message == null) {
            return null;
        }
        if (message.isContextMarker()) {
            return null;
        }
        String messageId = message.messageId();
        String clientMsgId = null;
        Role role = toRole(message.role());
        String text = message.text();
        String thinking = message.thinking();
        long time = message.time();
        MessageState state = MessageState.CONFIRMED;
        ToolView tool = message.isToolCall() ? toToolView(message) : null;
        return new MessageView(messageId, clientMsgId, role, text, thinking, time, state, tool);
    }

    private static ToolView toToolView(Message message) {
        if (message == null) {
            return null;
        }
        String callId = message.callId();
        String name = message.toolName();
        String argsSummary = message.toolArgs();
        ToolState state = toToolState(message.toolStatus());
        boolean truncated = false;
        return new ToolView(callId, name, argsSummary, state, truncated);
    }

    public static Role toRole(Message.Role role) {
        if (role == null) {
            return null;
        }
        Role result = null;
        switch (role) {
            case ROLE_USER -> result = Role.USER;
            case ROLE_AI -> result = Role.ASSISTANT;
            case ROLE_TOOL -> result = Role.TOOL_RESULT;
        }
        return result;
    }


    public static ToolState toToolState(Message.ToolStatus toolStatus) {
        if (toolStatus == null) {
            return null;
        }
        ToolState result = null;
        switch (toolStatus) {
            case OK ->  result = ToolState.OK;
            case ERROR ->   result = ToolState.ERROR;
            case RUNNING ->   result = ToolState.RUNNING;
            case INTERRUPTED ->    result = ToolState.INTERRUPTED;
            case STATUS_BG_DONE -> result = ToolState.BG_DONE;
            case STATUS_BG_KILLED ->  result = ToolState.BG_KILLED;
            case STATUS_BG_RUNNING ->   result = ToolState.BG_RUNNING;
        }
        return result;
    }

    public static UsageView toUsageView(Conversation.Usage usage) {
        long inputTokens = usage == null ? 0 : usage.inputTokens();
        long outputTokens = usage == null ? 0 : usage.outputTokens();
        double elapsedSeconds = usage == null ? 0 : usage.elapsedSeconds();
        return new UsageView(inputTokens, outputTokens, elapsedSeconds);
    }

    public static ReasoningEffort toReasoningEffort(ReasoningEffortEnum reasoningEffort) {
        if (reasoningEffort == null) {
            return null;
        }
        ReasoningEffort result = null;
        switch (reasoningEffort) {
            case NO -> result = ReasoningEffort.NO;
            case ON -> result = ReasoningEffort.ON;
            case LOW -> result = ReasoningEffort.LOW;
            case OFF -> result = ReasoningEffort.OFF;
            case HIGH -> result = ReasoningEffort.HIGH;
            case ULTRA -> result = ReasoningEffort.ULTRA;
            case MEDIUM -> result = ReasoningEffort.MEDIUM;
        }
        return result;
    }

    public static AgentMode toAgentMode(io.github.preschoolller.agentjava.spi.enums.AgentMode agentMode) {
        if (agentMode == null) {
            return null;
        }
        AgentMode result = null;
        switch (agentMode) {
            case CHAT -> result = AgentMode.CHAT;
            case EDIT -> result = AgentMode.EDIT;
            case PLAN -> result = AgentMode.PLAN;
            case FULL_ACCESS -> result = AgentMode.FULL_ACCESS;
        }
        return result;
    }
}

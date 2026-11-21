package io.github.preschoolller.agentjava.backend.provider.common.event;

import io.github.preschoolller.agentjava.backend.provider.common.ToolCall;

public record FunctionToolCallDoneEvent(String callId, ToolCall toolCall) implements BaseEvent {
}


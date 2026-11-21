package io.github.preschoolller.agentjava.backend.provider.common.event;

import io.github.preschoolller.agentjava.backend.provider.common.ToolCall;
import io.github.preschoolller.agentjava.spi.provider.event.BaseEvent;

public record FunctionToolCallDoneEvent(String callId, ToolCall toolCall) implements BaseEvent {
}


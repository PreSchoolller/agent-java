package io.github.preschoolller.agentjava.core.events;

import io.github.preschoolller.agentjava.core.enums.ToolState;

public record ToolStateChanged(String sessionId, String messageId, String callId, ToolState state,
                               String resultText, boolean truncated, long seq) implements StateEvent {
}
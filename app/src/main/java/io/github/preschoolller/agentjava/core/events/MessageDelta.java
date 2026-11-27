package io.github.preschoolller.agentjava.core.events;

public record MessageDelta(String sessionId, String messageId,
                           String textDelta, String thinkingDelta, long seq) implements StateEvent {
}
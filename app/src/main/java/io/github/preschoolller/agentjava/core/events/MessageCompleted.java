package io.github.preschoolller.agentjava.core.events;

public record MessageCompleted(String sessionId, String messageId, long seq) implements StateEvent {
}
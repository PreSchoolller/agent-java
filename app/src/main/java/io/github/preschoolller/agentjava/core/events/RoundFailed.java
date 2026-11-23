package io.github.preschoolller.agentjava.core.events;

public record RoundFailed(String sessionId, String reason, long seq) implements StateEvent {
}
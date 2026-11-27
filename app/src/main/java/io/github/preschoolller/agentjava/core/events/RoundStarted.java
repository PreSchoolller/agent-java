package io.github.preschoolller.agentjava.core.events;

public record RoundStarted(String sessionId, RoundCause cause, long seq) implements StateEvent {
}
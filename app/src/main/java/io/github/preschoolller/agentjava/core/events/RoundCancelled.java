package io.github.preschoolller.agentjava.core.events;

public record RoundCancelled(String sessionId, long seq) implements StateEvent {
}
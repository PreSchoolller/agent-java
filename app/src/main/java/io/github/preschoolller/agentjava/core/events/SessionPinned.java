package io.github.preschoolller.agentjava.core.events;

public record SessionPinned(String sessionId, boolean pinned, long seq) implements StateEvent {
}
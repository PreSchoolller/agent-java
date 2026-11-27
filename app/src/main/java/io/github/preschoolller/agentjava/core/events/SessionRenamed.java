package io.github.preschoolller.agentjava.core.events;

public record SessionRenamed(String sessionId, String title, long seq) implements StateEvent {
}
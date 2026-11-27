package io.github.preschoolller.agentjava.core.events;

public record Notice(String sessionId, NoticeLevel level, String text, long seq) implements StateEvent {
}
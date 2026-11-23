package io.github.preschoolller.agentjava.core.events;

public record HostRequestClosed(String requestId, String sessionId,
                                CloseReason reason, long seq) implements StateEvent {
}
package io.github.preschoolller.agentjava.core.events;

import io.github.preschoolller.agentjava.core.interfaces.HostRequests;

public record HostRequestOpened(String sessionId, HostRequests.Request request, long seq) implements StateEvent {
}
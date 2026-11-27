package io.github.preschoolller.agentjava.core.events;

import io.github.preschoolller.agentjava.core.records.SessionSummary;

public record SessionCreated(String sessionId, SessionSummary summary, long seq) implements StateEvent {
}
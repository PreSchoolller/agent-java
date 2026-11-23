package io.github.preschoolller.agentjava.core.events;

import io.github.preschoolller.agentjava.core.records.UsageView;

public record RoundFinished(String sessionId, UsageView usage, boolean chainEnded, long seq) implements StateEvent {
}
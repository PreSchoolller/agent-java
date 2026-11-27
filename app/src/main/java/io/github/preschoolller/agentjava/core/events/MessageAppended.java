package io.github.preschoolller.agentjava.core.events;

import io.github.preschoolller.agentjava.core.records.MessageView;

public record MessageAppended(String sessionId, MessageView message, long seq) implements StateEvent {
}
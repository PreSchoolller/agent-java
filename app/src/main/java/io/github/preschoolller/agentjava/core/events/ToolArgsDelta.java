package io.github.preschoolller.agentjava.core.events;

public record ToolArgsDelta(String sessionId, String messageId, String callId,
                            String delta, long seq) implements StateEvent {}

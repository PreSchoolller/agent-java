package io.github.preschoolller.agentjava.backend.provider.common.event;

public record FunctionToolCallStartEvent(String id, String callId, String name) implements BaseEvent {
}


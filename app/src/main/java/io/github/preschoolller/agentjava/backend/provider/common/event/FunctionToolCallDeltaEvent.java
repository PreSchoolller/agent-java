package io.github.preschoolller.agentjava.backend.provider.common.event;

public record FunctionToolCallDeltaEvent(String id, String argsDelta) implements BaseEvent {
}


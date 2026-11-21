package io.github.preschoolller.agentjava.spi.provider.event;

public record FunctionToolCallDeltaEvent(String id, String argsDelta) implements BaseEvent {
}


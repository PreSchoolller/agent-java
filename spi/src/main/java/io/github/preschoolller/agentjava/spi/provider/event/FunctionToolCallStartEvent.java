package io.github.preschoolller.agentjava.spi.provider.event;

public record FunctionToolCallStartEvent(String id, String callId, String name) implements BaseEvent {
}


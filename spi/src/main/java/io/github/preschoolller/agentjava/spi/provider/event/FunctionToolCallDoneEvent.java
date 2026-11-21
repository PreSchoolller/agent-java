package io.github.preschoolller.agentjava.spi.provider.event;

public record FunctionToolCallDoneEvent(String id, String callId) implements BaseEvent {
}


package io.github.preschoolller.agentjava.spi.provider.event;

public record ReasoningDeltaEvent(String id, String delta) implements BaseEvent {
}


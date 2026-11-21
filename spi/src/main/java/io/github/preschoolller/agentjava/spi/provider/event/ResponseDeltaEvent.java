package io.github.preschoolller.agentjava.spi.provider.event;

public record ResponseDeltaEvent(String id, String delta) implements BaseEvent {
}

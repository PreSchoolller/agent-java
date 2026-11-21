package io.github.preschoolller.agentjava.spi.provider.event;

public record ResponseDeltaDoneEvent(String id, String text) implements BaseEvent {
}

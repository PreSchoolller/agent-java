package io.github.preschoolller.agentjava.backend.provider.common.event;

public record ResponseDeltaDoneEvent(String id, String text) implements BaseEvent {
}

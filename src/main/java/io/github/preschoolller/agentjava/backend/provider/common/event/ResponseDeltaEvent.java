package io.github.preschoolller.agentjava.backend.provider.common.event;

public record ResponseDeltaEvent(String id, String delta) implements BaseEvent {
}

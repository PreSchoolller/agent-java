package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.core.events.StateEvent;

import java.util.function.Function;

public interface EventPublisher {

    void publish(Function<Long, StateEvent> function);
}

package io.github.preschoolller.agentjava.core.interfaces;

import io.github.preschoolller.agentjava.core.events.StateEvent;

public interface EventListener {
    void onEvent(StateEvent event);
}
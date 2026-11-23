package io.github.preschoolller.agentjava.core.interfaces;

import io.github.preschoolller.agentjava.core.events.StateEvent;

public interface AppEvents {
    AutoCloseable subscribe(long sinceSeq, EventListener listener);
}
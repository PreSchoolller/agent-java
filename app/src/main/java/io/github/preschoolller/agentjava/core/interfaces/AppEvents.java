package io.github.preschoolller.agentjava.core.interfaces;


public interface AppEvents {
    AutoCloseable subscribe(long sinceSeq, EventListener listener);
}
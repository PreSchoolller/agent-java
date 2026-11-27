package io.github.preschoolller.agentjava.core.interfaces;

import io.github.preschoolller.agentjava.core.records.SessionView;
import io.github.preschoolller.agentjava.core.records.Snapshot;

public interface AppView {
    Snapshot snapshot();

    SessionView session(String sessionId);
}
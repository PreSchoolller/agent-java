package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.core.interfaces.AppView;
import io.github.preschoolller.agentjava.core.records.SessionSummary;
import io.github.preschoolller.agentjava.core.records.SessionView;
import io.github.preschoolller.agentjava.core.records.Snapshot;

import java.util.List;

public class AppViewImpl implements AppView {
    @Override
    public Snapshot snapshot() {
        return null;
    }

    @Override
    public SessionView session(String sessionId) {
        return null;
    }

    @Override
    public List<SessionSummary> sessionsSince(long seq) {
        return List.of();
    }
}

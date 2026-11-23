package io.github.preschoolller.agentjava.core.interfaces;

import io.github.preschoolller.agentjava.core.records.SessionSummary;
import io.github.preschoolller.agentjava.core.records.SessionView;
import io.github.preschoolller.agentjava.core.records.Snapshot;

import java.util.List;

public interface AppView {
    Snapshot snapshot();

    SessionView session(String sessionId);

    List<SessionSummary> sessionsSince(long seq);
}
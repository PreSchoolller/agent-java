package io.github.preschoolller.agentjava.core.records;

import java.util.List;

public record Snapshot(long seq, List<SessionSummary> sessions) {
}

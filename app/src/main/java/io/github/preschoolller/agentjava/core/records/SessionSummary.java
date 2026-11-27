package io.github.preschoolller.agentjava.core.records;

import io.github.preschoolller.agentjava.core.enums.SessionStatus;

public record SessionSummary(String id, String title, boolean pinned, String workspace,
                             SessionStatus status, long updatedAt) {
}

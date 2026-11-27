package io.github.preschoolller.agentjava.core.records;

import io.github.preschoolller.agentjava.core.enums.ToolState;

public record ToolView(String callId, String name, String argsSummary, ToolState state,
                       boolean truncated) {
}

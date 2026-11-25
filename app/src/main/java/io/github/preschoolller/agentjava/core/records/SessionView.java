package io.github.preschoolller.agentjava.core.records;

import io.github.preschoolller.agentjava.core.enums.AgentMode;
import io.github.preschoolller.agentjava.core.enums.ReasoningEffort;
import io.github.preschoolller.agentjava.core.enums.SessionStatus;

import java.util.List;

public record SessionView(String id, String title, String workspace, SessionStatus status,
                          String modelName, String modelProfile,
                          ReasoningEffort effort, AgentMode mode,
                          UsageView usage, List<MessageView> messages, long seq) {
}

package io.github.preschoolller.agentjava.core.records;

import io.github.preschoolller.agentjava.core.enums.MessageState;
import io.github.preschoolller.agentjava.core.enums.Role;

public record MessageView(String messageId, String clientMsgId, Role role, String text,
                          String thinking, long time, MessageState state, ToolView tool) {
}

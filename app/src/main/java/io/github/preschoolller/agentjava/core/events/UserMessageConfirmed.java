package io.github.preschoolller.agentjava.core.events;

public record UserMessageConfirmed(String sessionId, String clientMsgId,
                                   String messageId, long seq) implements StateEvent {
}
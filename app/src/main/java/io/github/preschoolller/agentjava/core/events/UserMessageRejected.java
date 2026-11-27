package io.github.preschoolller.agentjava.core.events;

public record UserMessageRejected(String sessionId, String clientMsgId,
                                  String reason, long seq) implements StateEvent {
}
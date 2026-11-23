package io.github.preschoolller.agentjava.core.interfaces;

import java.util.List;

public interface HostRequests {
    sealed interface Answer permits Approve, Reject, TextAnswer {
    }

    record Approve() implements Answer {
    }

    record Reject(String reason) implements Answer {
    }

    record TextAnswer(String value) implements Answer {
    }

    sealed interface Payload permits ApprovalPayload, InputPayload, ConfirmPayload {
    }

    record ApprovalPayload(String toolName, String argsSummary, String argsRaw) implements Payload {
    }

    record InputPayload(String prompt, String defaultValue) implements Payload {
    }

    record ConfirmPayload(String title, String detail) implements Payload {
    }

    record Request(String requestId, String sessionId, String callId,
                   Kind kind, Payload payload, long deadlineEpochMs) {
    }

    enum Kind {TOOL_APPROVAL, USER_INPUT, CONFIRM}

    List<Request> pending();

    void answer(String requestId, Answer answer);

    void cancel(String requestId);
}
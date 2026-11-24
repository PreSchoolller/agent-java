package io.github.preschoolller.agentjava.frontend.tmp;

import io.github.preschoolller.agentjava.core.events.NoticeLevel;
import io.github.preschoolller.agentjava.core.interfaces.HostRequests;
import io.github.preschoolller.agentjava.core.records.UsageView;

public interface AgentSink {
    void setGenerating(String sessionId, boolean generating);

    AiWriter appendAi(String sessionId, String messageId);           // 句柄负责增量与收尾

    ToolWriter appendToolCall(String sessionId, String messageId, String toolName, String callId);

    void appendUser(String sessionId, String message);             // 外部注入的用户消息

    void usage(String sessionId, UsageView usage);                   // 用量胶囊

    void notify(String sessionId, NoticeLevel level, String text);   // sessionId 可空

    Boolean requestApproval(String sessionId, String messageId, String callId,
                            HostRequests.ApprovalPayload payload, boolean additionalRequest,
                            long deadlineEpochMs);                   // null = 无前端

    boolean onBackgroundDone(String sessionId, String callId, String output);

    void onBackgroundKilled(String sessionId, String callId);
}


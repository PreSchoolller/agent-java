package io.github.preschoolller.agentjava.core.interfaces;

import io.github.preschoolller.agentjava.core.enums.AgentMode;
import io.github.preschoolller.agentjava.core.enums.ReasoningEffort;

public interface AppCommands {
    // 会话
    CreateSessionResult createSession(String workspace, String reuseIfEmptySessionId); // null = 永不复用

    String ensureSession(String sessionId, String workspace);

    void renameSession(String sessionId, String title);

    void pinSession(String sessionId, boolean pinned);

    void deleteSession(String sessionId);

    void deleteSessionIfEmpty(String sessionId);      // 前端切走时调，幂等

    String forkSession(String sessionId, String fromMessageId);

    // 对话，全部异步、立即返回
    void submit(String sessionId, String clientMsgId, String text);

    void regenerate(String sessionId, String fromMessageId);

    void cancel(String sessionId);

    // 会话级配置
    void setWorkspace(String sessionId, String workspace);

    void setModel(String sessionId, String profileName, String modelName);

    void setReasoningEffort(String sessionId, ReasoningEffort effort);   // null = 回落全局默认

    void setAgentMode(String sessionId, AgentMode mode);                 // null = 同上

    record CreateSessionResult(String sessionId, boolean reused) {
    }
}
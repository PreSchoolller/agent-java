package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.core.enums.AgentMode;
import io.github.preschoolller.agentjava.core.enums.ReasoningEffort;
import io.github.preschoolller.agentjava.core.interfaces.AppCommands;

public class AppCommandsImpl implements AppCommands {
    @Override
    public CreateSessionResult createSession(String workspace, String reuseIfEmptySessionId) {
        return null;
    }

    @Override
    public String ensureSession(String sessionId, String workspace) {
        return "";
    }

    @Override
    public void renameSession(String sessionId, String title) {

    }

    @Override
    public void pinSession(String sessionId, boolean pinned) {

    }

    @Override
    public void deleteSession(String sessionId) {

    }

    @Override
    public void deleteSessionIfEmpty(String sessionId) {

    }

    @Override
    public String forkSession(String sessionId, String fromMessageId) {
        return "";
    }

    @Override
    public void submit(String sessionId, String clientMsgId, String text) {

    }

    @Override
    public void regenerate(String sessionId, String fromMessageId) {

    }

    @Override
    public void cancel(String sessionId) {

    }

    @Override
    public void setWorkspace(String sessionId, String workspace) {

    }

    @Override
    public void setModel(String sessionId, String profileName, String modelName) {

    }

    @Override
    public void setReasoningEffort(String sessionId, ReasoningEffort effort) {

    }

    @Override
    public void setAgentMode(String sessionId, AgentMode mode) {

    }
}

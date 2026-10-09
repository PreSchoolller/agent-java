package io.github.preschoolller.agentjava.backend.provider.common;

import java.util.ArrayList;
import java.util.List;

public class PromptParams {

    private String model;
    private ReasoningEffortEnum re;
    private List<RoleMessage> roleMessages;

    /**
     * if true, no tool use, purely chat.
     */
    private boolean justChat;

    private PromptParams() {}

    public String getModel() {
        return model;
    }

    public ReasoningEffortEnum getRe() {
        return re;
    }

    public List<RoleMessage> getRoleMessages() {
        return roleMessages;
    }

    public Boolean getJustChat() {
        return justChat;
    }

    public static PromptParams.Builder builder() {
        return new Builder();
    }

    public PromptParams.Builder toBuilder() {
        return new Builder(this);
    }

    public static class Builder {

        private Builder() {
            base = new PromptParams();
            base.roleMessages = new ArrayList<>();
        }

        public Builder(PromptParams promptParams) {
            this();
            base.model = promptParams.model;
            base.re = promptParams.re;
            base.roleMessages = promptParams.roleMessages;
            base.justChat = promptParams.justChat;
        }

        private final PromptParams base;

        public PromptParams build() {
            return base;
        }

        public Builder model(String model) {
            base.model = model;
            return this;
        }

        public Builder re(ReasoningEffortEnum reasoningEffortEnum) {
            base.re = reasoningEffortEnum;
            return this;
        }

        public Builder roleMessages(RoleMessage roleMessage) {
            base.roleMessages.add(roleMessage);
            return this;
        }

        public Builder justChat(boolean justChat) {
            base.justChat = justChat;
            return this;
        }
    }

    public record RoleMessage(RoleEnum role, String content, String toolArguments, String toolName, String callId) {
        public RoleMessage(RoleEnum role, String content) {
            this(role, content, "", "", "");
        }
    }
}

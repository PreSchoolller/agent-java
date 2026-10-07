package io.github.preschoolller.agentjava.backend.provider.common;

import java.util.ArrayList;
import java.util.List;

public class PromptParams {

    private String model;
    private ReasoningEffortEnum re;
    private List<RoleMessage> roleMessages;

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

    public static PromptParams.Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private Builder() {
            base = new PromptParams();
            base.roleMessages = new ArrayList<>();
        }

        private PromptParams base;

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
    }

    public record RoleMessage(RoleEnum role, String content) {}
}

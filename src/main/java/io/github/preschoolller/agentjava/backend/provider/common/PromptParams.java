package io.github.preschoolller.agentjava.backend.provider.common;

import java.util.ArrayList;
import java.util.List;

public class PromptParams {

    private String model;
    private ReasoningEffortEnum re;
    private List<RoleMessage> roleMessages;

    /**
     * 本轮的权限模式（输入区左下角）：审批策略据此决定要不要问（AgentMode#requiresApproval），
     * CHAT 还会让 buildParams 摘掉工具声明与历史工具轮次（纯聊天）。
     */
    private AgentMode agentMode;

    /**
     * 工具执行前的审批钩子（P0-4 权限确认卡）：返回 false 表示未放行，工具不执行，
     * 拒绝文本作为工具结果回给模型。允许实现里阻塞（provider 会在自己的执行线程上调用，
     * 绝不在 SSE 读流线程上）。null = 不询问，直接执行。
     */
    private ToolApprovalGate approvalGate;

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

    /** 未显式设置时按较严的 EDIT 处理（漏接线只会多问一次，不会不问就执行） */
    public AgentMode getAgentMode() {
        return agentMode == null ? AgentMode.EDIT : agentMode;
    }

    public ToolApprovalGate getApprovalGate() {
        return approvalGate;
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
            base.agentMode = promptParams.agentMode;
            base.approvalGate = promptParams.approvalGate;
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

        public Builder agentMode(AgentMode agentMode) {
            base.agentMode = agentMode;
            return this;
        }

        public Builder approvalGate(ToolApprovalGate approvalGate) {
            base.approvalGate = approvalGate;
            return this;
        }
    }

    public record RoleMessage(RoleEnum role, String content, String toolArguments, String toolName, String callId) {
        public RoleMessage(RoleEnum role, String content) {
            this(role, content, "", "", "");
        }
    }
}

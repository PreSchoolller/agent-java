package io.github.preschoolller.agentjava.backend.provider.common;

/**
 * 工具执行前的审批钩子（P0-4 权限确认卡，接线见 Main）：
 * provider 在自己的工具执行线程上调用（绝不在 SSE 读流线程上），实现可以阻塞等待用户点击。
 * 返回 true 放行执行；返回 false 工具不执行，拒绝文本作为工具结果回给模型。
 */
@FunctionalInterface
public interface ToolApprovalGate {

    String DENIED_RESULT = "用户拒绝了本次工具调用，工具未执行。请不要重试同一调用，向用户说明后等待指示。";

    boolean allow(ToolCall toolCall, boolean additionalRequest);
}

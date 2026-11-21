package io.github.preschoolller.agentjava.backend.provider.common;

import io.github.preschoolller.agentjava.spi.common.Constants;

/**
 * 工具执行前的审批钩子（P0-4 权限确认卡，接线见 Main）：
 * 在工具执行线程上调用（ToolCall.run，绝不在 SSE 读流线程上），实现可以阻塞等待用户点击。
 * 返回 true 放行执行；返回 false 工具不执行，拒绝文本作为工具结果回给模型。
 */
@FunctionalInterface
public interface ToolApprovalGate {

    String DENIED_RESULT = Constants.DENIED_RESULT;

    boolean allow(ToolCall toolCall, boolean additionalRequest);
}

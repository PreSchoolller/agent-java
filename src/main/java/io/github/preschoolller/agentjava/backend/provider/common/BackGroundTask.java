package io.github.preschoolller.agentjava.backend.provider.common;

/**
 * 后台任务，与前端的交互接口
 */
@FunctionalInterface
public interface BackGroundTask {

    boolean markDone(ToolCall toolCall, String out);
}

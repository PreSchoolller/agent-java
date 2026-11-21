package io.github.preschoolller.agentjava.backend.provider.common;

/**
 * 后台任务，与前端的交互接口
 */
@FunctionalInterface
public interface BackGroundTask {

    void markDone(String callId, String out);
}

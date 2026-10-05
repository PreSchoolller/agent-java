package io.github.preschoolller.agentjava.backend.provider.common;

/**
 * 对话的权限模式（输入区左下角选择，后端执行循环按此约束行为）：
 * PLAN 只读规划不动文件；EDIT 允许改文件；FULL_ACCESS 完全访问不受限。
 */
public enum AgentMode {
    PLAN,
    EDIT,
    FULL_ACCESS,
}

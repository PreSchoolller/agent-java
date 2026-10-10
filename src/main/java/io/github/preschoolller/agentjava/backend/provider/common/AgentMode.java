package io.github.preschoolller.agentjava.backend.provider.common;

import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

/**
 * 对话的权限模式（输入区左下角选择，后端执行循环按此约束行为）：
 * PLAN 只读规划不动文件；EDIT 允许改文件；FULL_ACCESS 完全访问不受限。
 */
public enum AgentMode {
    PLAN,
    EDIT,
    FULL_ACCESS,
    ;

    /**
     * 本次工具调用要不要先经用户确认（P0-4 权限确认卡的策略位）。
     * FULL_ACCESS 一律放行；EDIT 只问有副作用的（写）工具；
     * PLAN 是只读规划模式，任何工具执行都先问（read 之外的都改东西，read 也让人看一眼要读哪）。
     * tool 为 null 说明模型调了个不存在的名字，按最严的处理。
     */
    public boolean requiresApproval(Tool tool) {
        return switch (this) {
            case FULL_ACCESS -> false;
            case EDIT -> tool == null || !tool.readOnly();
            case PLAN -> true;
        };
    }
}

package io.github.preschoolller.agentjava.backend.tool.interfaces;

import java.util.Map;

public interface Tool {
    String name();                        // 模型看到并调用的名字
    String description();                 // 给模型看的说明（写清楚何时该用、参数含义）
    Map<String, Object> schema();     // 参数 JSON Schema（就是①里的 parameters）
    String execute(String arguments);     // 入参是模型给的 JSON 字符串，返回纯文本结果

    /**
     * 只读工具：不写盘、不改外部状态（如读文件）。权限模式据此决定要不要弹确认
     * （见 AgentMode#requiresApproval）。默认按"有副作用"处理——认错了方向是白问一次，
     * 把写操作认成只读是放过去一次，后者代价大得多。
     */
    default boolean readOnly() {
        return false;
    }
}

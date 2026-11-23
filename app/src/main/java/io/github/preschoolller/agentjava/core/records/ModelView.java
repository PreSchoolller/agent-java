package io.github.preschoolller.agentjava.core.records;

/**
 * 单个模型的元数据投影：{@code name} 是发给后端的 model 参数，其余是能力档案。
 *
 * <p>{@code ctxWindow} / {@code maxOutput} 空串 = 未设置；
 * {@code reasoning} 空串 = 跟随对话当前选择，非空时是 ReasoningEffort 的枚举名。
 */
public record ModelView(String name, String ctxWindow, String maxOutput, String reasoning,
                        boolean text, boolean image) {
}
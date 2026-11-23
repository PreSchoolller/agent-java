package io.github.preschoolller.agentjava.core.records;

import java.util.List;

/**
 * 一套后端请求配置（方案）的投影，{@code name} 即方案名。
 *
 * <p>{@code key} 是写侧字段：前端保存时传入，投影实现不回传（读出来恒为 null）。
 * 传 null 表示「不更改已落库的值」，不是「清空」。
 */
public record ProfileView(String name, String apiType, String backendUrl, String key,
                          String model, List<ModelView> models,
                          double temperature, int maxTokens, String systemPrompt) {
}
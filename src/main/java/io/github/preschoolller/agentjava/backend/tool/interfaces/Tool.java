package io.github.preschoolller.agentjava.backend.tool.interfaces;

import java.util.Map;

public interface Tool {
    String name();                        // 模型看到并调用的名字
    String description();                 // 给模型看的说明（写清楚何时该用、参数含义）
    Map<String, Object> schema();     // 参数 JSON Schema（就是①里的 parameters）
    String execute(String arguments);     // 入参是模型给的 JSON 字符串，返回纯文本结果
}

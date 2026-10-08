package io.github.preschoolller.agentjava.backend.tool.interfaces;

import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;

public interface Tool {
    String name();                        // 模型看到并调用的名字
    String description();                 // 给模型看的说明（写清楚何时该用、参数含义）
    FunctionTool.Parameters schema();     // 参数 JSON Schema（就是①里的 parameters）
    String execute(String arguments);     // 入参是模型给的 JSON 字符串，返回纯文本结果
    Boolean test(ResponseFunctionToolCall functionTool);// 是否是该工具调用
}

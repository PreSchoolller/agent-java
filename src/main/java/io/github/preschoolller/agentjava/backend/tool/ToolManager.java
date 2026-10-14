package io.github.preschoolller.agentjava.backend.tool;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;
import io.github.preschoolller.agentjava.backend.provider.common.ToolCall;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ToolManager {
    public static final Tool BAD_TOOL = BadTool.getInstance();
    public static final Tool EMPTY_TOOL = EmptyTool.getInstance();
    private static volatile ToolManager instance;
    public static List<Tool> tools = new ArrayList<>(List.of(
            ToolRead.getInstance(),
            ToolWslBash.getInstance()
    ));

    private ToolManager() {
    }

    public static ToolManager getInstance() {
        if (instance == null) {
            synchronized (ToolManager.class) {
                if (instance == null) {
                    instance = new ToolManager();
                }
            }
        }
        return instance;
    }

    public void register(Tool tool) {
        tools.add(tool);
    }

    public static Tool findByName(String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findAny().orElse(BAD_TOOL);
    }

    /** 只接受已注册的 Tool（buildParams 遍历 tools 时用）；BAD_TOOL.schema() 是 null，别拿占位工具进来 */
    public static FunctionTool getFunctionTool(Tool tool) {
        HashMap<String, JsonValue> parameters = new HashMap<>();
        if (tool.schema() != null) {
            tool.schema().forEach((k,v) -> parameters.put(k, JsonValue.from(v)));
        }
        FunctionTool functionTool = FunctionTool
                .builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(FunctionTool.Parameters.builder().additionalProperties(parameters).build())
                .strict(true)
                .build();
        return functionTool;
    }

    /** 只接受已注册的 Tool（buildParams 遍历 tools 时用）；BAD_TOOL.schema() 是 null，别拿占位工具进来 */
    public static ChatCompletionFunctionTool getChatCompletionFunctionTool(Tool tool) {
        HashMap<String, JsonValue> parameters = new HashMap<>();
        if (tool.schema() != null) {
            tool.schema().forEach((k,v) -> parameters.put(k, JsonValue.from(v)));
        }
        return ChatCompletionFunctionTool
                .builder()
                .function(FunctionDefinition.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .parameters(FunctionParameters.builder()
                                .putAllAdditionalProperties(parameters)
                                .build())
                        .strict(true)
                        .build())
                .build();
    }

    /** 执行一次工具调用：workspace 是会话绑定的工作区目录，透传给工具做相对路径解析 */
    public static String execute(ResponseFunctionToolCall functionTool, String workspace) {
        for (Tool tool : tools) {
            if (!tool.name().equals(functionTool.name())) {
                continue;
            }
            return tool.execute(functionTool.arguments(), workspace);
        }
        return BAD_TOOL.execute(functionTool.arguments(), workspace);
    }

    /** 执行一次工具调用：workspace 是会话绑定的工作区目录，透传给工具做相对路径解析 */
    public static String execute(ToolCall toolCall, String workspace) {
        if (toolCall == null || toolCall.getTool() == null || toolCall.getTool() instanceof BadTool) {
            return BAD_TOOL.execute("", workspace);
        }
        return toolCall.getTool().execute(toolCall.getArguments(), workspace);
    }
}

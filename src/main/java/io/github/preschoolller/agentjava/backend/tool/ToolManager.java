package io.github.preschoolller.agentjava.backend.tool;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

public class ToolManager {
    public static final Tool BAD_TOOL = BadTool.getInstance();
    public static final Tool EMPTY_TOOL = EmptyTool.getInstance();
    private static volatile ToolManager instance;
    static List<Tool> tools = new ArrayList<>(List.of(
            ToolRead.getInstance()
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

    public static Optional<Tool> findByName(String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    public static FunctionTool getFunctionTool(String name) {
        Optional<Tool> optionalTool = findByName(name);
        if (optionalTool.isPresent()) {
            Tool tool = optionalTool.get();
            HashMap<String, JsonValue> parameters = new HashMap<>();
            tool.schema().forEach((k,v) -> parameters.put(k, JsonValue.from(v)));
            FunctionTool functionTool = FunctionTool
                    .builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(FunctionTool.Parameters.builder().additionalProperties(parameters).build())
                    .strict(true)
                    .build();
            return functionTool;
        }
        return null;
    }

    public static String execute(ResponseFunctionToolCall functionTool) {
        for (Tool tool : tools) {
            if (!tool.name().equals(functionTool.name())) {
                continue;
            }
            return tool.execute(functionTool.arguments());
        }
        return "";
    }
}

package io.github.preschoolller.agentjava.backend.tool;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.List;
import java.util.Map;

import static io.github.preschoolller.agentjava.backend.tool.ToolManager.BAD_TOOL;

public class ToolChain implements Tool {

    private static volatile ToolChain instance;

    private ToolChain() {
    }

    public static ToolChain getInstance() {
        if (instance == null) {
            synchronized (ToolChain.class) {
                if (instance == null) {
                    instance = new ToolChain();
                }
            }
        }
        return instance;
    }

    @Override
    public String name() {
        return "tool chain";
    }

    @Override
    public String description() {
        return """
                除了本工具之外的其它所有可见工具的调用链，例如[grep, read]会将随后的grep工具调用结果传入read工具；
                """;
    }

    @Override
    public boolean readOnly() {
        return true; // 只读文件，无副作用：编辑模式下不用确认
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("tool_chain", List.of(
                        Map.of("type", "object",
                                "description", "工具调用及其参数",
                                "properties", Map.of("tool_name", Map.of("type", "string", "description", "工具名称"),
                                        "arguments", Map.of("type", "string", "description", "工具参数")
                                )
                        ))
                ),
                "required", List.of("tool_chain")
        );
    }

    @Override
    public String execute(String arguments, String workspace) {
        try {
            JsonNode jsonNode = new ObjectMapper().readTree(arguments);
            if (jsonNode == null) {
                return "json parse error: wrong schema";
            }
            JsonNode chain = jsonNode.path("chain");
            if (chain.isArray()) {
                StringBuilder result = new StringBuilder();
                ArrayNode jsonNodes = chain.withArray(JsonPointer.compile(""));
                for (JsonNode node : jsonNodes) {
                    String toolName = node.path("tool_name").asText();
                    Tool tool = null;
                    if ((tool = ToolManager.findByName(toolName)) == BAD_TOOL) {
                        return "invalid tool name : " + toolName;
                    }
                    String str = tool.execute(node.path("arguments").asText(), workspace);
                    result.append("tool :").append(toolName).append(" result:").append(str);
                }
                return result.toString();
            }
            return "chain is valid, performing tool chain";
        } catch (JsonProcessingException e) {
            return "json parse error: " + e.getMessage();
        }
    }
}

package io.github.preschoolller.agentjava.backend.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ToolRead implements Tool {

    private static ToolRead instance;

    private ToolRead() {
    }

    public static ToolRead getInstance() {
        if (instance == null) {
            synchronized (ToolRead.class) {
                if (instance == null) {
                    instance = new ToolRead();
                }
            }
        }
        return instance;
    }

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return "读取文本文件内容";
    }

    @Override
    public FunctionTool.Parameters schema() {
        return FunctionTool.Parameters
                .builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of("path", Map.of("type", "string", "description", "文件相对路径"))))
                .putAdditionalProperty("required", JsonValue.from(List.of("path")))
                .build();
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonNode jsonNode = new ObjectMapper().readTree(arguments);
            String path = jsonNode.path("path").asText();
            return Files.readString(Path.of(path));
        } catch (JsonProcessingException e) {
            return "json parse error: " + e.getMessage();
        } catch (IOException e) {
            return "file read error: " + e.getMessage();
        }
    }

    @Override
    public Boolean test(ResponseFunctionToolCall functionTool) {
        return name().equals(functionTool.name());
    }
}

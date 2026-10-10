package io.github.preschoolller.agentjava.backend.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class ToolRead implements Tool {

    private static volatile ToolRead instance;

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
    public boolean readOnly() {
        return true; // 只读文件，无副作用：编辑模式下不用确认
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string", "description", "文件相对路径")),
                "required", List.of("path")
        );
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonNode jsonNode = new ObjectMapper().readTree(arguments);
            if (jsonNode == null) {
                return "json parse error: wrong schema";
            }
            String path = jsonNode.path("path").asText();
            if (path == null || path.isBlank()) {
                return "json parse error: wrong path";
            }
            return Files.readString(Path.of(path));
        } catch (JsonProcessingException e) {
            return "json parse error: " + e.getMessage();
        } catch (IOException e) {
            return "file read error: " + e.getMessage();
        }
    }
}

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
        return "读取文本文件内容（相对路径按当前工作区解析）";
    }

    @Override
    public boolean readOnly() {
        return true; // 只读文件，无副作用：编辑模式下不用确认
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string", "description",
                        "文件路径，相对路径以当前工作区为基准")),
                "required", List.of("path")
        );
    }

    @Override
    public String execute(String arguments, String workspace) {
        try {
            JsonNode jsonNode = new ObjectMapper().readTree(arguments);
            if (jsonNode == null) {
                return "json parse error: wrong schema";
            }
            String path = jsonNode.path("path").asText();
            if (path == null || path.isBlank()) {
                return "json parse error: wrong path";
            }
            Path target = Path.of(path);
            // 相对路径以会话工作区为基准，绝对路径原样用；workspace 缺失时按进程工作目录解析
            if (!target.isAbsolute() && workspace != null && !workspace.isBlank()) {
                target = Path.of(workspace).resolve(target);
            }
            return Files.readString(target);
        } catch (JsonProcessingException e) {
            return "json parse error: " + e.getMessage();
        } catch (IOException e) {
            return "file read error: " + e.getMessage();
        }
    }
}

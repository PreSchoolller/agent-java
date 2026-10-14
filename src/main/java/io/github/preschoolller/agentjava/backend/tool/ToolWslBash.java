package io.github.preschoolller.agentjava.backend.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.github.preschoolller.agentjava.backend.provider.common.Constants.MAPPER;

public class ToolWslBash implements Tool {

    private static final String COMMAND = "command";

    private static volatile ToolWslBash instance;

    private ToolWslBash() {
    }

    public static ToolWslBash getInstance() {
        if (instance == null) {
            synchronized (ToolWslBash.class) {
                if (instance == null) {
                    instance = new ToolWslBash();
                }
            }
        }
        return instance;
    }

    @Override
    public String name() {
        return "wsl";
    }

    @Override
    public String description() {
        return "在当前工作区执行wsl bash -c命令，能用单引号的情况尽量不用双引号，windows会截断双引号后的空格后的所有内容";
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(COMMAND, Map.of("type", "string", "description",
                        "bash命令")),
                "required", List.of(COMMAND)
        );
    }

    @Override
    public String execute(String arguments, String workspace) {
        try {
            JsonNode jsonNode = MAPPER.readTree(arguments);
            if (jsonNode == null) {
                return "json parse error: wrong schema";
            }
            String command = jsonNode.path(COMMAND).asText();
            if (command == null || command.isBlank()) {
                return "json parse error: wrong command";
            }
            ProcessBuilder processBuilder = new ProcessBuilder("wsl", "bash", "-c", command);
            processBuilder.directory(new File(workspace));
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            String out;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                out = reader.lines().collect(Collectors.joining("\n"));
            }
            process.waitFor(60, TimeUnit.SECONDS);
            int exitCode = process.exitValue();
            System.out.println("bash参数：" + command + " bash 执行结果：" + out + " 退出码：" + exitCode);
            return out.isBlank() ? "tool use result is null, exit code is: " + exitCode : (out + "\nexit code: " + exitCode).strip();
        } catch (JsonProcessingException e) {
            return "json parse error: " + e.getMessage();
        } catch (IOException e) {
            return "file read error: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "tool use error: bash execute was interrupted, maybe try again later.";
        }
    }
}

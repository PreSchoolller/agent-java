package io.github.preschoolller.agentjava.backend.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;
import org.tinylog.Logger;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.github.preschoolller.agentjava.backend.constant.Constants.MAPPER;

public class ToolWslBash implements Tool {

    private static final String COMMAND = "command";
    private static final int TIMEOUT = 60; // seconds

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
        return "在当前工作区执行wsl bash -c命令，如果要使用引号，只能用单引号";
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
            boolean exited = process.waitFor(TIMEOUT, TimeUnit.SECONDS);
            String timeOut = "";
            if (!exited) {
                process.destroyForcibly();
                timeOut = "\nprocess terminated after " + TIMEOUT + " seconds";
            }
            int exitCode = process.exitValue();
            out = out.isBlank() ? "tool use result is null" : out;
            out += timeOut;
            out += "\nexit code: " + exitCode;
            Logger.trace("bash参数：{} bash 执行结果：{}", command, out);
            return out;
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

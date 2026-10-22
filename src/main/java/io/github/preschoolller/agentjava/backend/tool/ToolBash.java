package io.github.preschoolller.agentjava.backend.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;
import org.tinylog.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ToolBash implements Tool {

    private static final String COMMAND = "command";
    private static final int TIMEOUT = 60; // seconds

    private static volatile List<String> bashHead;
    private static final List<String> BASH_HEAD = List.of("bash");
    private static final List<List<String>> WINDOWS_BASH_HEADS = List.of(
            BASH_HEAD,
            List.of("wsl", "bash"),
            List.of("C:\\Program Files\\Git\\bin\\bash.exe")
    );
    private static volatile boolean valid = true;

    private ToolBash() {
    }

    public static ToolBash getInstance() {
        return InstanceHolder.instance;
    }

    private static class InstanceHolder {
        private static final ToolBash instance = new ToolBash();
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return """
                在当前工作区执行bash -c命令。
                如果agent运行环境（由工作区路径判断）非windows，bash为原生实现。
                如果agent运行环境为Windows，则bash实现查找顺序(bash -> wsl bash -> git bash)。
                由于上述三种bash实现的路径表示不一样，可先pwd判断格式再执行绝对路径命令书写。
                Windows下只能用单引号!Windows下只能用单引号!Windows下只能用单引号!。
                """;
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
        defineBash();
        if (!valid) {
            return "tool error: no valid bash command found in system";
        }
        try {
            JsonNode jsonNode = Constants.getObjectMapper().readTree(arguments);
            if (jsonNode == null) {
                return "json parse error: wrong schema";
            }
            String command = jsonNode.path(COMMAND).asText();
            if (command == null || command.isBlank()) {
                return "json parse error: wrong command";
            }
            ArrayList<String> bash = new ArrayList<>(bashHead);
            bash.add("-c");
            bash.add(command);
            ProcessBuilder processBuilder = new ProcessBuilder(bash);
            processBuilder.directory(new File(workspace));
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            String out;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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
            return "bash execute error: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "tool use error: bash execute was interrupted, maybe try again later.";
        }
    }

    private void defineBash() {
        if (bashHead != null) {
            return;
        }
        synchronized (this) {
            if (bashHead != null || !valid) {
                return;
            }
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("windows")) {
                for (List<String> head : WINDOWS_BASH_HEADS) {
                    if (tryBash(head)) {
                        bashHead = head;
                        return;
                    }
                }
                if (bashHead == null) {
                    valid = false;
                }
            } else if (tryBash(BASH_HEAD)) {
                bashHead = BASH_HEAD;
            } else {
                valid = false;
            }
        }
    }

    private static boolean tryBash(List<String> head) {
        ArrayList<String> list = new ArrayList<>(head);
        list.add("-version");
        ProcessBuilder processBuilder = new ProcessBuilder(list);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String out = reader.lines().collect(Collectors.joining("\n"));
                boolean exited = process.waitFor(TIMEOUT, TimeUnit.SECONDS);
                if (exited && out.toLowerCase().contains("gnu bash")) {
                    return true;
                }
                process.destroyForcibly();
            }
        } catch (IOException | InterruptedException e) {
            Logger.info("测试bash工具{}出错", head);
            Logger.debug("测试bash工具{}出错: {}", head, e);
        }
        return false;
    }
}

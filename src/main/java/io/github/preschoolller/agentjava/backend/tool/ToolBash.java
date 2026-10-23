package io.github.preschoolller.agentjava.backend.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.provider.common.ThreadPoolFactory;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import org.tinylog.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ToolBash implements Tool {

    private static final String COMMAND = "command";
    private static final int TIMEOUT = 60; // seconds
    private static final int SHORT_TIMEOUT = 2; // seconds 用于检测 bash 命令超时
    private static final int THRESHOLD = 4096; // 超长命令转临时文件执行

    private static final List<String> BASH_HEAD = List.of("bash");
    private static final List<String> W_BASH_HEAD = List.of("bash");
    private static final List<String> WSL_BASH_HEAD = List.of("wsl", "bash");
    private static final List<String> GIT_BASH_HEAD = List.of("C:\\Program Files\\Git\\bin\\bash.exe");

    private static final List<List<String>> WINDOWS_BASH_HEADS = List.of(
            W_BASH_HEAD,
            WSL_BASH_HEAD,
            GIT_BASH_HEAD
    );
    private static volatile boolean valid = true;
    private static volatile List<String> bashHead;

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
                在工作区执行bash命令。
                如果agent运行环境（由工作区路径判断）非windows，bash为原生实现。
                如果agent运行环境为Windows，则bash实现查找顺序(bash -> wsl bash -> git bash)。
                由于上述三种bash实现的路径表示不一样，可先pwd判断格式再执行绝对路径命令书写。
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
            ProcessBuilder processBuilder = assembleProcessBuilder(command, workspace);
            Process process = processBuilder.start();
            StringBuffer out = new StringBuffer();
            Future<?> futureRead = ThreadPoolFactory.getToolCallExecutor().submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    reader.lines().forEach(out::append);
                } catch (IOException e) {
                    out.append("bash execute error while reading result: ").append(e.getMessage());
                }
            });
            // TODO 有些就是要sleep等结果的，或者是等长时间构建的，这超时时间（以及前端的等待时间）可能不够，或者说直接不允许做那些事儿？有待设计。。。
            boolean exited = process.waitFor(TIMEOUT, TimeUnit.SECONDS);
            String timeOut = "";
            if (!exited) {
                process.destroyForcibly();
                futureRead.cancel(true);
                process.waitFor(SHORT_TIMEOUT, TimeUnit.SECONDS);
                timeOut = "\nprocess terminated after " + TIMEOUT + " seconds";
            }
            int exitCode = process.exitValue();

            out.append(out.isEmpty() ? "(no output)" : "");
            out.append(timeOut);
            out.append("\nexit code: ").append(exitCode);
            Logger.trace("bash参数：{} bash 执行结果：{}", command, out);
            return out.toString();
        } catch (JsonProcessingException e) {
            return "json parse error: " + e.getMessage();
        } catch (IOException e) {
            return "bash execute error: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "tool use error: bash execute was interrupted, maybe try again later.";
        }
    }

    private static ProcessBuilder assembleProcessBuilder(String command, String workSpace) throws IOException {
        ArrayList<String> bash = new ArrayList<>(bashHead);
        ProcessBuilder processBuilder;
        if (command.length() > THRESHOLD || needPutInFile(command)) {
            Path workDir = Path.of(workSpace);
            Path tmpDir = Constants.TEMP_DIR.resolve(workDir.getFileName().toString());
            Files.createDirectories(tmpDir);

            String tmpName = UUID.randomUUID().toString();
            Path tmpFile = Files.createTempFile(tmpDir, tmpName, ".sh");
            Files.writeString(tmpFile, command);

            bash.add(buildTmpFilePath(tmpFile));
            Logger.info("转文件执行：{}", tmpFile.toAbsolutePath());
        } else {
            String base64 = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8));
            bash.add("-c");
            bash.add("echo " + base64 + " | base64 -d | bash");
        }
        processBuilder = new ProcessBuilder(bash);
        processBuilder.directory(new File(workSpace));
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }

    private static String buildTmpFilePath(Path tmpFile) {
        Path absolutePath = tmpFile.toAbsolutePath();
        if (BASH_HEAD == bashHead) {
            return absolutePath.toString();
        }
        Path root = absolutePath.getRoot();
        String disk = "";
        if (root != null && !root.toString().isBlank()) {
            disk = "/" + root.toString().substring(0, 1).toLowerCase();
        }
        if (W_BASH_HEAD == bashHead || WSL_BASH_HEAD == bashHead) {
            StringBuilder sb = new StringBuilder();
            sb.append("/mnt");
            sb.append(disk);
            for (Path path : absolutePath) {
                sb.append("/");
                sb.append(path.getFileName());
            }
            return sb.toString();
        }
        if (GIT_BASH_HEAD == bashHead) {
            StringBuilder sb = new StringBuilder();
            sb.append(disk);
            for (Path path : absolutePath) {
                sb.append("/");
                sb.append(path.getFileName());
            }
            return sb.toString();
        }
        return absolutePath.toString();
    }

    // TODO 或许有不适合base64管道的？先留一个代码配置
    private static boolean needPutInFile(String command) {
        String lowerCase = command.toLowerCase();
        Pattern pattern = Pattern.compile(AppSettings.get().getBashFilePattern());
        return pattern.matcher(lowerCase).find();
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
        list.add("--version");
        ProcessBuilder processBuilder = new ProcessBuilder(list);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String out = reader.lines().collect(Collectors.joining("\n"));
                boolean exited = process.waitFor(SHORT_TIMEOUT, TimeUnit.SECONDS);
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

package io.github.preschoolller.agentjava.backend.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.maintenance.ShutDownJob;
import io.github.preschoolller.agentjava.backend.provider.common.ThreadPoolFactory;
import io.github.preschoolller.agentjava.backend.tool.interfaces.BackGroundTaskDone;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ToolBash implements Tool {

    private static final String COMMAND = "command";
    private static final String BACKGROUND = "background";
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
    private static final int BACKGROUND_OUT_THRESHOLD = 32 << 10;    // 后台任务输出长度，实际大小乘以 2B
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
                "properties", Map.of(COMMAND, Map.of("type", "string", "description", "bash命令"),
                        BACKGROUND, Map.of("type", "boolean", "description", "是否要作为后台进程运行，当次调用仅返回提交成功/失败，后台任务完成后结果会插入额外信息中")
                ),
                "required", List.of(COMMAND)
        );
    }

    @Override
    public String execute(String arguments, String workspace) {
        return executeEnhance(arguments, workspace, null, null);
    }

    public String executeEnhance(String arguments, String workspace, String callId, BackGroundTaskDone bgTaskDone) {
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
            boolean asBackGroundTask = jsonNode.path(BACKGROUND).asBoolean(false);
            ProcessBuilder processBuilder = assembleProcessBuilder(command, workspace);
            if (asBackGroundTask) {
                return executeBackGround(arguments, workspace, processBuilder, bgTaskDone, callId);
            }
            return executeForeGround(processBuilder, arguments, workspace);
        } catch (JsonProcessingException e) {
            return "json parse error: " + e.getMessage();
        }
    }

    private static String executeForeGround(ProcessBuilder processBuilder, String arguments, String workspace) {
        StringBuffer out = new StringBuffer();
        try {
            Process process = processBuilder.start();
            Future<?> futureRead = ThreadPoolFactory.getReadBashOutExecutor().submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    reader.lines().forEach(line -> {
                        if (out.isEmpty()) {
                            out.append(line);
                        } else {
                            out.append("\n").append(line);
                        }
                    });
                } catch (IOException e) {
                    out.append("bash execute error while reading result: ").append(e.getMessage());
                }
            });
            boolean exited = process.waitFor(TIMEOUT, TimeUnit.SECONDS);
            String timeOut = "";
            boolean doubleCheck = false;
            if (!exited) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                futureRead.cancel(true);
                if (process.waitFor(SHORT_TIMEOUT, TimeUnit.SECONDS)) {
                    doubleCheck = true;
                }
                timeOut = "\nprocess terminated after " + TIMEOUT + " seconds";
            }
            try {
                futureRead.get(SHORT_TIMEOUT, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                // 最差情况 60秒等待->2秒强杀->2秒读缓冲流 依然到了这里，前端早已超时终止检查，结果已不重要，此处直接强杀
                // 好情况 前方无等待顺利完成->2秒读缓冲流 到了这里，有那么长的缓冲流吗？强行截断，直接强杀
                Logger.info("force cut off");
                futureRead.cancel(true);
                out.append("\n(result was cut off for long waiting)");
            }
            out.append(out.isEmpty() ? "(no output)" : "");
            out.append(timeOut);
            if (exited || doubleCheck) {
                out.append("\n(exit code: ").append(process.exitValue()).append(")");
            } else {
                out.append("\n(did not exit)");
            }
            Logger.trace("bash参数：{} bash 执行结果：{}", arguments, out);
            return out.toString();
        } catch (IOException e) {
            Logger.error("start process error, arguments {}, workspace {}", arguments, workspace);
            out.append("\nbackground task failed to start: ").append(e.getMessage());
            return out.toString();
        } catch (InterruptedException e) {
            Logger.error("bash execute was interrupted, arguments {}, workspace {}", arguments, workspace);
            Thread.currentThread().interrupt();
            return "tool use error: bash execute was interrupted, maybe try again later.";
        }
    }

    private String executeBackGround(String arguments, String workspace, ProcessBuilder processBuilder, BackGroundTaskDone bgTaskDone, String callId) {
        AtomicBoolean ready = new AtomicBoolean();
        AtomicLong pid = new AtomicLong();
        try {
            Future<?> backFuture = ThreadPoolFactory.getBgTaskExecutor().submit(() -> {
                StringBuffer out = new StringBuffer();
                Process holder = null;
                try {
                    Process process = ShutDownJob.registerBgProcess(processBuilder.start());
                    holder = process;
                    pid.set(process.pid());
                    ready.set(true);
                    Logger.info("background task submitted, arguments {}, workspace {}, pid {}", arguments, workspace, process.pid());
                    Future<?> futureRead = ThreadPoolFactory.getBgTaskReadOutExecutor().submit(() -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            reader.lines().takeWhile(line -> {
                                if (out.length() > BACKGROUND_OUT_THRESHOLD) {
                                    out.append("\n(out put truncated for exceeds").append(BACKGROUND_OUT_THRESHOLD * 2).append("B)");
                                    return false;
                                }
                                return true;
                            }).forEach(line -> {
                                if (out.isEmpty()) {
                                    out.append(line);
                                } else {
                                    out.append("\n").append(line);
                                }
                            });
                        } catch (IOException e) {
                            out.append("bash execute error while reading result: ").append(e.getMessage());
                        } finally {
                            process.descendants().forEach(ProcessHandle::destroyForcibly);
                            process.destroyForcibly();
                        }
                    });
                    futureRead.get();
                } catch (IOException e) {
                    Logger.error("start process error, arguments {}, workspace {}", arguments, workspace);
                    out.append("\nbackground task failed to start: ").append(e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Logger.error("bash execute was interrupted, arguments {}, workspace {}", arguments, workspace);
                } catch (ExecutionException e) {
                    Logger.error("future read exception, arguments {}, workspace {}, exception message {}", arguments, workspace, e.getMessage());
                    out.append("\nbackground task exception: ").append(e.getMessage());
                } finally {
                    if (holder != null) {
                        ShutDownJob.removeBgProcess(holder);
                        holder.descendants().forEach(ProcessHandle::destroyForcibly);
                        holder.destroyForcibly();
                    }
                    bgTaskDone.markDone(out.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            Logger.info("background task failed to submit, arguments {}, workspace {}", arguments, workspace);
            return "background task submitted failed: task pool is full, try again later";
        }
        String pidStr = ", pid: " + (ready.get() ? pid : "(not start yet)");
        return "background task submitted successfully, id: " + callId + pidStr;
    }

    private static ProcessBuilder assembleProcessBuilder(String command, String workSpace) {
        ArrayList<String> bash = new ArrayList<>(bashHead);
        ProcessBuilder processBuilder;
        boolean byFile = false;
        if (command.length() > THRESHOLD || needPutInFile(command)) {
            Path workDir = Path.of(workSpace);
            Path tmpDir = Constants.TEMP_DIR.resolve(workDir.getFileName().toString());
            Path tmpFile = null;
            try {
                Files.createDirectories(tmpDir);

                String tmpName = UUID.randomUUID().toString();
                tmpFile = Files.createTempFile(tmpDir, tmpName, ".sh");
                Files.writeString(tmpFile, command);
                bash.add(buildTmpFilePath(tmpFile));
                Logger.info("转文件执行：{}", tmpFile.toAbsolutePath());
                byFile = true;
            } catch (IOException e) {
                Logger.error("创建临时文件失败，尝试回退base64");
            }
        }
        if (!byFile) {
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

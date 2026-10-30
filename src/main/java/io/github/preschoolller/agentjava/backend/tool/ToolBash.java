package io.github.preschoolller.agentjava.backend.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.maintenance.ShutDownJob;
import io.github.preschoolller.agentjava.backend.maintenance.TaskHandle;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class ToolBash implements Tool {

    private static final String COMMAND = "command";
    private static final String BACKGROUND = "background";
    private static final int TIMEOUT = 60; // seconds
    private static final int SHORT_TIMEOUT = 2; // seconds 用于检测 bash 命令超时
    private static final int MILI_TIMEOUT = 500; // mili seconds 用于检测等待后台 pid 超时
    private static final int THRESHOLD = 4096; // 超长命令转临时文件执行

    enum BashHead {
        L(List.of("bash")),
        W(List.of("bash")),
        WSL(List.of("wsl", "bash")),
        GIT(List.of("C:\\Program Files\\Git\\bin\\bash.exe")),
        CUSTOM(null)
        ;

        final List<String> head;

        BashHead(List<String> bashHead) {
            this.head = bashHead;
        }
    }

    private static final List<BashHead> WINDOWS_BASH_HEADS = List.of(
            BashHead.W,
            BashHead.WSL,
            BashHead.GIT
    );
    private static final int BACKGROUND_OUT_THRESHOLD = 32 << 10;    // 后台任务输出长度，实际大小乘以 2B
    private static volatile boolean valid = true;
    private static volatile BashHead bashHead;
    private static volatile List<String> customBashHead;

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
    public boolean supportsBackGroundTask() {
        return true;
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
        return execute(arguments, workspace, null, null);
    }

    @Override
    public String execute(String arguments, String workspace, String callId, BackGroundTaskDone bgTaskDone) {
        defineBash();
        if (!valid) {
            return "tool error: no valid bash command found in system";
        }
        try {
            JsonNode jsonNode = Constants.getObjectMapper().readTree(arguments);
            if (jsonNode.isNull()) {
                return "json parse error: wrong schema";
            }
            String command = jsonNode.path(COMMAND).asText();
            if (command.isBlank()) {
                return "json parse error: wrong command";
            }
            boolean asBackGroundTask = jsonNode.path(BACKGROUND).asBoolean(false);
            workspace = workspace == null || workspace.isBlank() ? Constants.DEFAULT_WORKSPACE_DIR.toString() : workspace;
            ProcessBuilder processBuilder = assembleProcessBuilder(command, workspace, bashHead, Constants.TEMP_DIR);
            if (asBackGroundTask) {
                return executeBackGround(arguments, workspace, processBuilder, bgTaskDone, callId);
            }
            return executeForeGround(processBuilder, arguments, workspace, callId);
        } catch (JsonProcessingException e) {
            return "json parse error: " + e.getMessage();
        } catch (Throwable e) {
            Logger.error(e, "tool bash execute error, arguments {}, workspace {}, call id {}", arguments, workspace, callId);
            return "agent app error: unknown";
        }
    }

    /** 前台执行：进程注册进 ShutDownJob（callId 键），工具卡"终止"按钮可中途杀掉它——
     *  waitFor 随进程死亡立即返回，已读输出照常回填，结果附"(已手动终止)"标记 */
    private static String executeForeGround(ProcessBuilder processBuilder, String arguments, String workspace, String callId) {
        StringBuffer out = new StringBuffer();
        try {
            Process process = ShutDownJob.registerBgProcess(callId, new TaskHandle(out, processBuilder.start(), false));
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
            if (ShutDownJob.wasKilled(callId)) {
                // 用户从工具卡手动终止：exit code 是 kill 码没参考价值，注明真实原因
                //（Conversation.updateToolResult 按此标记把卡片翻"已终止"徽标）
                out.append("\n(已手动终止)");
            }
            Logger.trace("bash参数：{} bash 执行结果：{}", arguments, out);
            return out.toString();
        } catch (IOException e) {
            Logger.error("start process error, arguments {}, workspace {}", arguments, workspace);
            out.append("\nforeground task failed to start: ").append(e.getMessage());
            return out.toString();
        } catch (InterruptedException e) {
            Logger.error("bash execute was interrupted, arguments {}, workspace {}", arguments, workspace);
            Thread.currentThread().interrupt();
            return "tool use error: bash execute was interrupted, maybe try again later.";
        } finally {
            ShutDownJob.removeBgProcess(callId);
        }
    }

    private static String executeBackGround(String arguments, String workspace, ProcessBuilder processBuilder, BackGroundTaskDone bgTaskDone, String callId) {
        if (bgTaskDone == null || callId == null || callId.isBlank()) {
            Logger.error("illegal arguments: bgTaskDone {}; callId {};", bgTaskDone, callId);
            return "tool use error: unknown app error.";
        }
        CompletableFuture<Long> pid = new CompletableFuture<>();
        try {
            Future<?> backFuture = ThreadPoolFactory.getBgTaskExecutor().submit(() -> {
                StringBuffer out = new StringBuffer();
                Process holder = null;
                try {
                    Process process = ShutDownJob.registerBgProcess(callId, new TaskHandle(out, processBuilder.start(), true));
                    holder = process;
                    pid.complete(process.pid());
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
                        holder.descendants().forEach(ProcessHandle::destroyForcibly);
                        holder.destroyForcibly();
                        ShutDownJob.removeBgProcess(callId);
                    }
                    bgTaskDone.markDone(out.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            Logger.info("background task failed to submit, arguments {}, workspace {}", arguments, workspace);
            return "background task submitted failed: task pool is full, try again later";
        }
        String pidS = "(not start yet)";
        try {
            pidS = String.valueOf(pid.get(MILI_TIMEOUT, TimeUnit.MILLISECONDS));
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Logger.debug("exception encounter while waiting pid, message ignored");
        }
        String pidStr = ", pid: " + pidS;
        return "background task submitted successfully, id: " + callId + pidStr;
    }

    static ProcessBuilder assembleProcessBuilder(String command, String workSpace, BashHead bashHead, Path tempDir) {
        List<String> head = BashHead.CUSTOM.equals(bashHead) ? customBashHead : bashHead.head;
        ArrayList<String> bash = new ArrayList<>(head);
        ProcessBuilder processBuilder;
        boolean byFile = false;
        if (command.length() > THRESHOLD || needPutInFile(command)) {
            Path workDir = Path.of(workSpace);
            Path tmpDir = tempDir.resolve(workDir.getFileName().toString());
            Path tmpFile = null;
            try {
                Files.createDirectories(tmpDir);

                String tmpName = UUID.randomUUID().toString();
                tmpFile = Files.createTempFile(tmpDir, tmpName, ".sh");
                tmpFile.toFile().deleteOnExit();
                Files.writeString(tmpFile, command);
                bash.add(buildTmpFilePath(tmpFile, bashHead));
                Logger.info("转文件执行：{}", tmpFile.toAbsolutePath());
                byFile = true;
            } catch (IOException e) {
                Logger.error("创建临时文件失败，尝试回退base64");
            }
        }
        if (!byFile) {
            bash = new ArrayList<>(head);
            String base64 = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8));
            bash.add("-c");
            bash.add("echo " + base64 + " | base64 -d | bash");
        }
        processBuilder = new ProcessBuilder(bash);
        processBuilder.directory(new File(workSpace));
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }

    static String buildTmpFilePath(Path tmpFile, ToolBash.BashHead bashHead) {
        Path absolutePath = tmpFile.toAbsolutePath();
        if (BashHead.L.equals(bashHead)) {
            return absolutePath.toString();
        }
        Path root = absolutePath.getRoot();
        String disk = "";
        if (root != null && !root.toString().isBlank()) {
            disk = "/" + root.toString().substring(0, 1).toLowerCase();
        }
        switch (bashHead) {
            case W, WSL -> {
                StringBuilder sb = new StringBuilder();
                sb.append("/mnt");
                sb.append(disk);
                for (Path path : absolutePath) {
                    sb.append("/");
                    sb.append(path.getFileName());
                }
                return sb.toString();
            }
            case GIT -> {
                StringBuilder sb = new StringBuilder();
                sb.append(disk);
                for (Path path : absolutePath) {
                    sb.append("/");
                    sb.append(path.getFileName());
                }
                return sb.toString();
            }
            default -> {
                return absolutePath.toString();
            }
        }
    }

    /**
     * 命令文本命中配置的正则时转临时文件执行（设置面板「工具」页的"文件正则"）：替代
     * echo base64 | base64 -d | bash 管道，规避不适合走管道的命令（heredoc 等）。
     * 未配置（null）= 仅超长命令转文件。AppSettings 存入前已校验正则，这里仍兜一层：
     * 手改配置文件的坏值按不匹配处理，不让 PatternSyntaxException 打穿每次工具调用。
     */
    static boolean needPutInFile(String command) {
        String pattern = AppSettings.get().getBashFilePattern();
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        try {
            return Pattern.compile(pattern).matcher(command.toLowerCase()).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    static void defineBash() {
        exploreBash(true, false);
    }

    /**
     * 不定死，不检查结果，作用只是拉起应用比如 wsl
     */
    public static void exploreBash() {
        exploreBash(false, false);
    }

    static void exploreBash(boolean define, boolean redefine) {
        if (bashHead != null && define) {
            return;
        }
        if (!define) {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                tryBash(BashHead.W.head, true, 0, null);
                tryBash(BashHead.WSL.head, true, 0, null);
            }
            return;
        }
        synchronized (ToolBash.class) {
            if (bashHead != null || !valid && !redefine) {
                return;
            }
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("win")) {
                for (BashHead head : WINDOWS_BASH_HEADS) {
                    if (tryBash(head.head)) {
                        bashHead = head;
                        return;
                    }
                }
                if (bashHead == null) {
                    valid = false;
                }
            } else if (tryBash(BashHead.L.head)) {
                bashHead = BashHead.L;
            } else {
                valid = false;
            }
        }
    }

    private static boolean tryBash(List<String> head) {
        return tryBash(head, false, SHORT_TIMEOUT, TimeUnit.SECONDS);
    }

    private static boolean tryBash(List<String> head, boolean warmup, int timeout, TimeUnit timeUnit) {
        if (head == null || head.isEmpty()) {
            return false;
        }
        ArrayList<String> list = new ArrayList<>(head);
        list.add("--version");
        ProcessBuilder processBuilder = new ProcessBuilder(list);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            if (warmup) {
                process.destroyForcibly();
                return false;
            }
            timeout = timeout == 0 ? SHORT_TIMEOUT : timeout;
            timeUnit = timeUnit == null ? TimeUnit.SECONDS : timeUnit;
            // 假定(TODO此处依赖假定)上面的warmup已经拉起了耗时启动的bash（如wsl），定时任务兜底强杀进程，waitFor等进程结果
            ThreadPoolFactory.getTimeoutScheduler().schedule(process::destroyForcibly, timeout, timeUnit);
            String out;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                out = reader.readLine();
            }
            boolean exited = process.waitFor(timeout, timeUnit);
            if (exited && out != null && out.toLowerCase().contains("gnu bash")) {
                return true;
            }
            process.destroyForcibly();
        } catch (IOException | InterruptedException e) {
            Logger.info("测试bash工具{}出错", head);
            Logger.debug("测试bash工具{}出错: {}", head, e);
        }
        return false;
    }

    /**
     * 自定义bash可执行程序位置，可设计一个前端自定义功能，待设计。
     * 但是自定义的 buildTmpFilePath 不太好搞，TODO
     * @param bashPath 自定义bash位置
     * @return 添加是否成功
     */
    public boolean setBashHead(String bashPath) {
        boolean result = tryBash(List.of(bashPath), false, 10, TimeUnit.SECONDS);
        if (!result) {
            return false;
        }
        synchronized (ToolBash.class) {
            customBashHead = List.of(bashPath);
            bashHead = BashHead.CUSTOM;
            valid = true;
        }
        return true;
    }
}

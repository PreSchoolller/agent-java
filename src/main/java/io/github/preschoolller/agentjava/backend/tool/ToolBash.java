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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.stream.Collectors;

public class ToolBash implements Tool {

    private static final String COMMAND = "command";
    private static final String BACKGROUND = "background";
    private static final int TIMEOUT = 60; // seconds
    private static final int SHORT_TIMEOUT = 2; // seconds 用于检测 bash 命令超时
    private static final int MILI_TIMEOUT = 500; // mili seconds 用于检测等待后台 pid 超时
    private static final int THRESHOLD = 4096; // 超长命令转临时文件执行

    enum BashHead {
        L(List.of("bash")),
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
            BashHead.WSL,
            BashHead.GIT
    );
    private static final int BACKGROUND_OUT_THRESHOLD = 32 << 10;    // 后台任务输出长度，实际大小乘以 2B
    private static volatile boolean valid = true;
    private static volatile BashHead bashHead;
    private static volatile List<String> customBashHead;
    /** 最近一次探测过的自定义 bash 路径（成败都记）：defineBash 按它去重，避免配置没变时
     *  每次工具调用都重新拉起进程探测（探测最坏 10 秒）。设置面板保存路径变更会直接下发
     *  setBashHead（必然重探），这里只挡 defineBash 的惰性补齐路径 */
    private static volatile String attemptedCustomPath;

    private static final Logger LOG = LoggerFactory.getLogger(ToolBash.class);

    private static final String WSL = "WSL";
    private static final String GIT_BASH = "GITBASH";
    private static final String CUSTOM = "CUSTOM:";

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
                如果agent运行环境为Windows，则bash实现查找顺序(wsl bash -> git bash)。
                用户也可以在应用设置里指定自定义bash可执行程序，配置了则优先使用。
                由于windows上两种种bash实现的路径表示不一样，可先pwd判断格式再执行绝对路径命令书写。
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
            LOG.error("tool bash execute error, arguments {}, workspace {}, call id {}", arguments, workspace, callId, e);
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
                LOG.info("force cut off");
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
            LOG.trace("bash参数：{} bash 执行结果：{}", arguments, out);
            return out.toString();
        } catch (IOException e) {
            LOG.error("start process error, arguments {}, workspace {}", arguments, workspace);
            out.append("\nforeground task failed to start: ").append(e.getMessage());
            return out.toString();
        } catch (InterruptedException e) {
            LOG.error("bash execute was interrupted, arguments {}, workspace {}", arguments, workspace);
            Thread.currentThread().interrupt();
            return "tool use error: bash execute was interrupted, maybe try again later.";
        } finally {
            ShutDownJob.removeBgProcess(callId);
        }
    }

    private static String executeBackGround(String arguments, String workspace, ProcessBuilder processBuilder, BackGroundTaskDone bgTaskDone, String callId) {
        if (bgTaskDone == null || callId == null || callId.isBlank()) {
            LOG.error("illegal arguments: bgTaskDone {}; callId {};", bgTaskDone, callId);
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
                    LOG.info("background task submitted, arguments {}, workspace {}, pid {}", arguments, workspace, process.pid());
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
                    LOG.error("start process error, arguments {}, workspace {}", arguments, workspace);
                    out.append("\nbackground task failed to start: ").append(e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.error("bash execute was interrupted, arguments {}, workspace {}", arguments, workspace);
                } catch (ExecutionException e) {
                    LOG.error("future read exception, arguments {}, workspace {}, exception message {}", arguments, workspace, e.getMessage());
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
            LOG.info("background task failed to submit, arguments {}, workspace {}", arguments, workspace);
            return "background task submitted failed: task pool is full, try again later";
        }
        String pidS = "(not start yet)";
        try {
            pidS = String.valueOf(pid.get(MILI_TIMEOUT, TimeUnit.MILLISECONDS));
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOG.debug("exception encounter while waiting pid, message ignored");
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
                LOG.info("转文件执行：{}", tmpFile.toAbsolutePath());
                byFile = true;
            } catch (IOException e) {
                LOG.error("创建临时文件失败，尝试回退base64");
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
        if (BashHead.WSL.equals(bashHead)) {
            processBuilder.environment().put("WSL_UTF8", "1");
        }
        return processBuilder;
    }

    static String buildTmpFilePath(Path tmpFile, BashHead bashHead) {
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
            case WSL -> {
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

    /** 自定义 bash 配置是否已从落盘恢复过：恢复只做一次（首个工具调用时惰性触发），
     *  之后运行期变更一律走 uiInteractive（前端落盘 + 工具应用），不回头重读设置 */
    private static volatile boolean customRestored;

    /**
     * 定头入口（每次工具调用先进这里）：首个调用先惰性恢复落盘的自定义 bash 配置，
     * 再按默认链探测。已定头或此前探测全败则直接返回。
     */
    void defineBash() {
        if (!customRestored) {
            synchronized (ToolBash.class) {
                if (!customRestored) {
                    customRestored = true;
                    applyConfiguredCustomBash();
                }
            }
        }
        exploreBash(true, false);
    }

    private void applyConfiguredCustomBash() {
        String input = null;
        try {
            input = getPersistentInput();
        } catch (Throwable t) {
            return; // 设置侧不可用（裸类路径回归等）不挡 bash 工具本身的默认链，踩坑 #26 同源
        }
        if (input == null || input.isBlank()) {
            return;
        }
        synchronized (ToolBash.class) {
            if (WSL.equals(input)) {
                bashHead = BashHead.WSL;
            } else if (GIT_BASH.equals(input)) {
                bashHead = BashHead.GIT;
            } else if (input.startsWith("CUSTOM:")) {
                String path = input.substring(7);
                if (path.isBlank()) {
                    if (BashHead.CUSTOM.equals(bashHead)) {
                        clearCustomBash();
                    }
                    return;
                }
                if (!path.equals(attemptedCustomPath)) {
                    setBashHead(path);
                }
            }
        }
    }

    /**
     * 不定死，不检查结果，作用只是拉起应用比如 wsl
     */
    public static void exploreBash() {
        exploreBash(false, false);
    }

    static void exploreBash(boolean define, boolean redefine) {
        if (!define) {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                tryBash(BashHead.WSL.head, TIMEOUT, TimeUnit.SECONDS);
            }
            return;
        }
        synchronized (ToolBash.class) {
            if (!redefine && (bashHead != null || !valid)) {
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
        return tryBash(head, SHORT_TIMEOUT, TimeUnit.SECONDS);
    }

    private static boolean tryBash(List<String> head, int timeout, TimeUnit timeUnit) {
        if (head == null || head.isEmpty()) {
            return false;
        }
        ArrayList<String> list = new ArrayList<>(head);
        list.add("--version");
        ProcessBuilder processBuilder = new ProcessBuilder(list);
        processBuilder.environment().put("WSL_UTF8", "1");
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            timeout = timeout <= 0 ? SHORT_TIMEOUT : timeout;
            timeUnit = timeUnit == null ? TimeUnit.SECONDS : timeUnit;
            ThreadPoolFactory.getTimeoutScheduler().schedule(process::destroyForcibly, timeout, timeUnit);
            String out;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                out = reader.lines().collect(Collectors.joining("\n"));
            }
            boolean exited = process.waitFor(timeout, timeUnit);
            if (exited && out.toLowerCase().contains("gnu bash")) {
                return true;
            }
            process.destroyForcibly();
        } catch (IOException | InterruptedException e) {
            LOG.info("测试bash工具{}出错", head);
            LOG.debug("测试bash工具{}出错: ", head, e);
        }
        return false;
    }

    private static boolean setBashHead(String bashPath) {
        if (bashPath == null || bashPath.isBlank()) {
            return false;
        }
        attemptedCustomPath = bashPath; // 成败都记：defineBash 按它去重，同一路径不重复探测
        boolean result = tryBash(List.of(bashPath), 10, TimeUnit.SECONDS);
        if (!result) {
            clearCustomBash();
            return false;
        }
        synchronized (ToolBash.class) {
            customBashHead = List.of(bashPath);
            bashHead = BashHead.CUSTOM;
            valid = true;
        }
        return true;
    }

    /**
     * 清除自定义 bash，复位按默认链重新探测（redefine 语义）——设置清空自定义路径、或配置的
     * 路径探测失败时调用。redefine=true 让此前探测全失败（valid=false）的场景也有重试机会。
     */
    private static void clearCustomBash() {
        synchronized (ToolBash.class) {
            customBashHead = null;
            bashHead = null;
        }
        exploreBash(true, true);
    }

    @Override
    public String uiTips() {
        return """
               输入示例：
               1. “” 空/空白 字符串触发bash重选。
               2. “WSL” 指定WSL为bash工具，若WSL有问题则工具拒绝执行之后的一切调用。建议开一个wsl bash窗口保持wsl在线，不然会有启动开销。
               3. “GITBASH” 指定git bash为bash工具，若内置git bash路径有问题，则工具拒绝执行之后的一切调用。
               4. “CUSTOM:{your_bash_path}” 指定自定义bash可执行文件地址，若有问题，则仍使用内部定义的bash地址。
               """;
    }

    @Override
    public String uiInteractive(String input) {
        if (input == null || input.isBlank()) {
            exploreBash(true, true);
            return "已执行bash重选，当前：" + (bashHead == null ? "null" : bashHead.head.toString());
        }
        if (input.equals(WSL)) {
            // 显式指定=用户拍板，把 valid 一起复位给一次机会：此前探测全败（valid=false）时
            // 光换头不抬 valid，execute 会继续拒调，与"已重新指定"的反馈自相矛盾。
            // 若真有问题，下次 execute 探测失败会再把 valid 按下去（uiTips 描述的语义）
            synchronized (ToolBash.class) {
                bashHead = BashHead.WSL;
                valid = true;
            }
            return "已重新指定bash为wsl bash";
        }
        if (input.equals(GIT_BASH)) {
            synchronized (ToolBash.class) {
                bashHead = BashHead.GIT;
                valid = true;
            }
            return "已重新指定bash为git bash";
        }
        if (input.startsWith(CUSTOM)) {
            String bashPath = input.substring(CUSTOM.length());
            boolean result = setBashHead(bashPath);
            if (result) {
                return "已重新指定bash成功";
            }
            return "指定自定义bash失败，仍使用内部bash，当前：" + bashHead.head;
        }
        return "还没实现呢。。。";
    }
}

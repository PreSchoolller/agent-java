package io.github.preschoolller.agentjava.backend.maintenance;

import io.github.preschoolller.agentjava.backend.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * 按需启用，保持wsl存活，不检查wsl是否存在。
 */
public class HoldWsl {

    private static final List<String> ARGS = List.of("wsl", "--exec", "sleep", "infinity");
    /**
     * 只复写，不删除
     */
    private static final Path HOLDER_PID = Constants.APP_USER_DIR.resolve("wsl_holder_pid");
    private static final Logger LOG = LoggerFactory.getLogger(HoldWsl.class);
    private static volatile Thread running;
    public static final Consumer<Void> OFF = i -> HoldWsl.off();

    private HoldWsl() {
    }

    public static synchronized void on() {
        if (running != null && running.isAlive()) {
            return;
        }
        Thread thread = new Thread(HoldWsl::startAndHold);
        thread.setDaemon(true);
        thread.setName("hold-wsl");
        thread.start();
        running = thread;
        ShutDownJob.addShutDownHook(OFF);
    }

    public static synchronized void off() {
        interrupt();
        ShutDownJob.remove(OFF);
    }

    private static synchronized void interrupt() {
        if (running == null || !running.isAlive()) {
            return;
        }
        running.interrupt();
        running = null;
    }

    private static void detectIfExist() throws InterruptedException {
        boolean holderExist = Files.exists(HOLDER_PID);
        long pid = 0;
        if (holderExist) {
            try (RandomAccessFile holder = new RandomAccessFile(HOLDER_PID.toFile(), "r")) {
                pid = holder.readLong();
            } catch (IOException ignored) {
            }
        }
        if (pid <= 0) {
            return;
        }
        ProcessHandle.of(pid).ifPresent(processHandle -> {
            if (!processHandle.isAlive()) {
                return;
            }
            // info().arguments()获取不到参数，暂时就直接强杀吧，风险在于或许会有坏蛋会把不该杀的pid写进了这里。
            // Get-CimInstance Win32_Process -Filter "ProcessId=1234" | Select-Object CommandLine 这个命令倒是可以获取到参数，留作后续优化吧。
            processHandle.descendants().forEach(ProcessHandle::destroyForcibly);
            processHandle.destroyForcibly();
        });
    }

    private static void startAndHold() {
        Process process = null;
        try {
            detectIfExist();
            while (true) {
                process = createNewProcess();
                int exitCode = process.waitFor();
                LOG.info("wsl hold up exit with code {}", exitCode);
            }
        } catch (IOException e) {
            LOG.info("wsl hold up got io exception", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("wsl hold up got interrupted", e);
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static Process createNewProcess() throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(ARGS);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = processBuilder.start();
        try (RandomAccessFile holder = new RandomAccessFile(HOLDER_PID.toFile(), "rw")) {
            holder.writeLong(process.pid());
        }
        return process;
    }
}

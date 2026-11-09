package io.github.preschoolller.agentjava.backend.maintenance;

import io.github.preschoolller.agentjava.backend.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
    private static final Consumer<Void> OFF = i -> HoldWsl.interrupt();

    private HoldWsl() {
    }

    public static synchronized void on() {
        if (running != null && running.isAlive()) {
            return;
        }
        ShutDownJob.removeShutDownHook(OFF);
        if (!ShutDownJob.addShutDownHook(OFF)) {
            return;
        }
        Thread thread = new Thread(HoldWsl::startAndHold);
        thread.setDaemon(true);
        thread.setName("hold-wsl");
        thread.start();
        running = thread;
    }

    public static synchronized void off() {
        interrupt();
        ShutDownJob.removeShutDownHook(OFF);
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
            if (argsMatch(processHandle.pid())) {
                try {
                    processHandle.onExit().get();
                } catch (InterruptedException e) {
                    LOG.info("existing wsl holder got interrupted", e);
                    Thread.currentThread().interrupt();
                    processHandle.descendants().forEach(ProcessHandle::destroyForcibly);
                    processHandle.destroyForcibly();
                } catch (ExecutionException ignored) {
                }
            }
        });
    }

    private static boolean argsMatch(long pid) {
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-CimInstance Win32_Process -Filter ProcessId=" + pid + ").CommandLine");
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = processBuilder.start();
            process.waitFor(10, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            for (String arg : ARGS) {
                if (!output.contains(arg)) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.info("perform args match got interrupted", e);
        } catch (IOException e) {
            LOG.error("perform args match got io exception", e);
        } catch (Throwable throwable) {
            LOG.error("perform args match got exception", throwable);
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
        return false;
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

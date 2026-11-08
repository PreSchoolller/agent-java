package io.github.preschoolller.agentjava.backend.maintenance;

import io.github.preschoolller.agentjava.backend.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

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

    private HoldWsl() {
    }

    public static HoldWsl getInstance() {
        return InstanceHolder.instance;
    }

    private static class InstanceHolder {
        private static final HoldWsl instance = new HoldWsl();
    }

    public synchronized void on() {
        if (running != null && running.isAlive()) {
            return;
        }
        Thread thread = new Thread(this::startAndHold);
        thread.setDaemon(true);
        thread.setName("hold-wsl");
        thread.start();
        running = thread;
        Runtime.getRuntime().addShutdownHook(new Thread(this::off));
    }

    public synchronized void off() {
        if (running == null || !running.isAlive()) {
            return;
        }
        running.interrupt();
        running = null;
    }

    private void detectIfExist() throws InterruptedException {
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
            ProcessHandle.Info info = processHandle.info();
            info.arguments().ifPresent(args -> {
                if (ARGS.size() != args.length) {
                    return;
                }
                int index = 0;
                for (String arg : args) {
                    if (!ARGS.get(index++).equals(arg)) {
                        return;
                    }
                }
                try {
                    processHandle.onExit().get();
                } catch (InterruptedException e) {
                    LOG.info("existing wsl holder got interrupted", e);
                    Thread.currentThread().interrupt();
                    processHandle.descendants().forEach(ProcessHandle::destroyForcibly);
                    processHandle.destroyForcibly();
                } catch (ExecutionException ignored) {
                }
            });
        });
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private void startAndHold() {
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

    private Process createNewProcess() throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(ARGS);
        processBuilder.redirectInput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = processBuilder.start();
        try (RandomAccessFile holder = new RandomAccessFile(HOLDER_PID.toFile(), "rw")) {
            holder.writeLong(process.pid());
        }
        return process;
    }
}

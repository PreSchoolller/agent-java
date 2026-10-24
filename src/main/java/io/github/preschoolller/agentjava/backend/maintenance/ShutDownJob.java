package io.github.preschoolller.agentjava.backend.maintenance;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 维护一些需要在应用关闭时做的事情，比如关闭创建的后台进程
 */
public class ShutDownJob {
    private static final Set<Process> bgProcesses = new HashSet<>();

    public static synchronized Process registerBgProcess(Process process) {
        if (process == null) {
            return process;
        }
        bgProcesses.add(process);
        return process;
    }

    public static synchronized void removeBgProcess(Process process) {
        if (process == null) {
            return;
        }
        bgProcesses.remove(process);
    }

    public static synchronized Set<Process> getBgProcesses() {
        return Collections.unmodifiableSet(bgProcesses);
    }

    public static synchronized void clean() {
        bgProcesses.forEach(process -> {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        });
        bgProcesses.clear();
    }
}

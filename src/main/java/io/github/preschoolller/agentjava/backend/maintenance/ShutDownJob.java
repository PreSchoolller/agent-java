package io.github.preschoolller.agentjava.backend.maintenance;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 维护一些需要在应用关闭时做的事情：按 callId 管理在册的前台/后台进程，
 * 支持"终止"（工具卡按钮）与退出时兜底清理
 */
public class ShutDownJob {
    private static final Map<String, TaskHandle> bgProcesses = new HashMap<>();
    /** 被 kill() 终止过的 callId：前台工具调用据此在结果里注明"已手动终止"；
     *  removeBgProcess 时一并清除 */
    private static final Set<String> killedCallIds = new HashSet<>();

    public static synchronized Process registerBgProcess(String callId, TaskHandle taskHandle) {
        if (taskHandle == null) {
            return null;
        }
        bgProcesses.put(callId, taskHandle);
        return taskHandle.process();
    }

    public static synchronized void removeBgProcess(String callId) {
        if (callId == null) {
            return;
        }
        bgProcesses.remove(callId);
        killedCallIds.remove(callId);
    }

    /** 终止在册进程（含子孙）；返回被终止的 TaskHandle，不在册（已结束/未知 callId）返回
     *  null。调用方据返回值的 background() 决定编排：后台任务走 bg-killed 流程，前台任务
     *  由工具调用自行收尾（waitFor 随进程死亡返回，结果附"已手动终止"标记） */
    public static synchronized TaskHandle kill(String callId) {
        TaskHandle taskHandle = bgProcesses.get(callId);
        if (taskHandle == null) {
            return null;
        }
        killedCallIds.add(callId);
        killF(taskHandle.process());
        return taskHandle;
    }

    /** 该 callId 是否被 kill() 终止过（在册期间有效） */
    public static synchronized boolean wasKilled(String callId) {
        return callId != null && killedCallIds.contains(callId);
    }

    public static synchronized String getBufferedOutput(String callId) {
        TaskHandle taskHandle = bgProcesses.get(callId);
        if (taskHandle == null) {
            return "";
        }
        return taskHandle.output().toString();
    }

    public static synchronized void clean() {
        bgProcesses.forEach((callId, taskHandle) -> {
            if (taskHandle != null) {
                killF(taskHandle.process());
            }
        });
        bgProcesses.clear();
        killedCallIds.clear();
    }

    private static void killF(Process process) {
        if (process == null) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}

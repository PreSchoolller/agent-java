package io.github.preschoolller.agentjava;

import io.github.preschoolller.agentjava.backend.maintenance.ShutDownJob;
import io.github.preschoolller.agentjava.backend.maintenance.WarmUpStuff;
import io.github.preschoolller.agentjava.frontend.SwingApp;

import java.lang.reflect.InvocationTargetException;

/**
 * 应用入口。前端经 {@link SwingApp} 启动，业务能力全部由 {@code core.interfaces.AgentApp} 提供；
 * 内核实现就绪前传 null，界面只读。
 */
public class Main {

    public static void main(String[] args) {
        // 内核实现就绪后：SwingApp.start(new YourAgentApp(...));
        SwingApp.start(null);
        Runtime.getRuntime().addShutdownHook(new Thread(ShutDownJob::clean));
        // 暂定这里执行。
        WarmUpStuff.warmup();
    }

    /**
     * 外部会话定位（dsh-shim 的 create 落点）。public 是给插件经 AgentApiServer.Delegate
     * 回调的；实现就绪后转调 {@code AgentApp.ensureSession}。
     */
    public static String apiEnsureSession(String sessionId, String workspace)
            throws InterruptedException, InvocationTargetException {
        throw new IllegalStateException("核心未就绪：AgentApp 尚无实现");
    }

    /**
     * 外部注入（dsh-shim 的 prompt 落点）。实现就绪后转调 {@code AgentApp.submit}。
     */
    public static String apiInject(String sessionId, String text)
            throws InterruptedException, InvocationTargetException {
        throw new IllegalStateException("核心未就绪：AgentApp 尚无实现");
    }
}

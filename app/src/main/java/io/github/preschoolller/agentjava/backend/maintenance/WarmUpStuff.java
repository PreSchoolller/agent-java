package io.github.preschoolller.agentjava.backend.maintenance;

import io.github.preschoolller.agentjava.Main;
import io.github.preschoolller.agentjava.api.AgentJavaSessionProvider;
import io.github.preschoolller.agentjava.api.AgentJavaSessions;
import io.github.preschoolller.agentjava.backend.mcp.McpManager;
import io.github.preschoolller.agentjava.backend.tool.ToolBash;
import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.spi.PluginPersistentHelper;
import io.github.preschoolller.agentjava.spi.PluginShutDownJobHelper;
import io.github.preschoolller.agentjava.spi.ToolHelper;

import java.util.concurrent.CompletableFuture;

/**
 * 应用启动后的一些预热和准备工作，不应影响已在进行的工作。
 */
public class WarmUpStuff {

    /** 外部会话入口（dsh-shim 等）的宿主实现：落到 Main 里与用户输入同链路的方法 */
    private static final AgentJavaSessionProvider HOST_SESSION = new AgentJavaSessionProvider() {
        @Override
        public String ensureSession(String sessionId, String workspace) throws Exception {
            return Main.apiEnsureSession(sessionId, workspace);
        }

        @Override
        public String inject(String sessionId, String text) throws Exception {
            return Main.apiInject(sessionId, text);
        }
    };

    public static void warmup() {
        injectToSpi();
        PluginLoader.load();
        CompletableFuture.runAsync(ToolBash::exploreBash);
        McpManager.loadMcpToolsAsync();
    }

    private static void injectToSpi() {
        PluginPersistentHelper.inject(PluginManager::getPersistentInput);
        PluginShutDownJobHelper.inject(ShutDownJob::addShutDownHook, ShutDownJob::removeShutDownHook);
        ToolHelper.inject(ToolManager::shouldRevealToolInfos);
        AgentJavaSessions.install(HOST_SESSION);
    }
}

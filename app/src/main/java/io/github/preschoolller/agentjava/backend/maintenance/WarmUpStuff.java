package io.github.preschoolller.agentjava.backend.maintenance;

import io.github.preschoolller.agentjava.backend.mcp.McpManager;
import io.github.preschoolller.agentjava.backend.tool.ToolBash;
import io.github.preschoolller.agentjava.spi.PluginPersistentConsumer;
import io.github.preschoolller.agentjava.spi.PluginShutDownJobHelper;

import java.util.concurrent.CompletableFuture;

/**
 * 应用启动后的一些预热和准备工作，不应影响已在进行的工作。
 */
public class WarmUpStuff {
    public static void warmup() {
        injectToSpi();
        PluginLoader.load();
        CompletableFuture.runAsync(ToolBash::exploreBash);
        McpManager.loadMcpToolsAsync();
    }

    private static void injectToSpi() {
        PluginPersistentConsumer.inject(PluginManager::getPersistentInput);
        PluginShutDownJobHelper.injectAdd(ShutDownJob::addShutDownHook);
        PluginShutDownJobHelper.injectRemove(ShutDownJob::removeShutDownHook);
    }
}

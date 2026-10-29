package io.github.preschoolller.agentjava.backend.maintenance;

import io.github.preschoolller.agentjava.backend.tool.ToolBash;
import io.github.preschoolller.agentjava.backend.tool.ToolManager;

/**
 * 应用启动后的一些预热和准备工作，不应影响已在进行的工作。
 */
public class WarmUpStuff {
    public static void warmup() {
        ToolManager.loadExternal();
        ToolBash.exploreBash();
    }
}

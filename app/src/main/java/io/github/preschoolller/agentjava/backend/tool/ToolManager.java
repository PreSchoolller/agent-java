package io.github.preschoolller.agentjava.backend.tool;

import io.github.preschoolller.agentjava.backend.mcp.McpTool;
import io.github.preschoolller.agentjava.spi.tool.Tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ToolManager {
    public static final Tool BAD_TOOL = BadTool.getInstance();
    public static final Tool EMPTY_TOOL = EmptyTool.getInstance();
    public static final Map<String, Tool> tools = new ConcurrentHashMap<>(Map.of(
            ToolRead.getInstance().name(), ToolRead.getInstance(),
            ToolBash.getInstance().name(), ToolBash.getInstance()
    ));

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ToolManager.class);

    private ToolManager() {
    }

    /**
     * 允许插件覆盖同名内部工具，便于增强
     * @param object 工具
     */
    public static void register(Object object) {
        if (!(object instanceof Tool tool)) {
            return;
        }
        if (tool.name() == null) {
            return;
        }
        Tool preValue = tools.put(tool.name(), tool);
        if (preValue != null) {
            LOG.info("tool {} was exchanged", tool.name());
        }
    }

    public static Tool findByName(String name) {
        return tools.getOrDefault(name, BAD_TOOL);
    }

    /**
     * 目前规划：
     * 1. 历史中用过的工具不受前端未勾选影响
     * 2. enabled 为 null，默认全部；为空，则不返回结果（历史非空不受影响）
     * @param history 历史中用到的工具名称
     * @param enabled 前端勾选的工具名称
     * @return 应当拼接进请求的工具列表
     */
    public static Collection<Tool> shouldRevealTools(Collection<String> history, Collection<String> enabled) {
        if (enabled == null) {
            return Collections.unmodifiableCollection(tools.values());
        }
        Set<String> names = new HashSet<>(enabled);
        names.addAll(history);
        List<Tool> result = new ArrayList<>();
        for (String name : names) {
            Tool tool = findByName(name);
            if (tool instanceof BadTool) {
                continue;
            }
            result.add(tool);
        }
        return Collections.unmodifiableList(result);
    }

    public static void unregister(McpTool unavailableTool) {
        tools.remove(unavailableTool.name());
    }
}

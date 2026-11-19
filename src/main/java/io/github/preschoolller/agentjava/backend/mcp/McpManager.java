package io.github.preschoolller.agentjava.backend.mcp;

import io.github.preschoolller.agentjava.backend.maintenance.ShutDownJob;
import io.github.preschoolller.agentjava.backend.mcp.interfaces.BaseMcp;
import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import io.github.preschoolller.agentjava.frontend.store.McpCatalog;
import io.github.preschoolller.agentjava.frontend.store.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class McpManager {

    /**
     * key: server name
     */
    private static final Map<String, BaseMcp> mcpPools = new ConcurrentHashMap<>();
    private static final AppSettings appSettings = AppSettings.get();
    private static final Logger LOG = LoggerFactory.getLogger(McpManager.class);
    /**
     * key: server name
     */
    private static final Map<String, McpCatalog.ServerRuntime> currentState = new ConcurrentHashMap<>();

    static {
        appSettings.addChangeListener(McpManager::loadMcpToolsAsync);
        ShutDownJob.addShutDownHook(i -> cleanup());
        setCallBack();
    }

    private McpManager() {
    }

    public static void loadMcpToolsAsync() {
        CompletableFuture.runAsync(McpManager::loadMcpTools);
    }

    /** synchronized：变更监听与 warmup 都会异步触发，串行化防止两次 diff 交错（复用/摘除判断基于同一份 pool 快照） */
    public static synchronized void loadMcpTools() {
        List<McpServer> mcpServers = appSettings.getMcpServers();
        Set<McpConfig> tmpNew = new HashSet<>(mcpServers.size());
        for (McpServer mcpServer : mcpServers) {
            McpConfig mcpConfig = convertMcpServerToMcpConfig(mcpServer);
            BaseMcp existing = mcpPools.get(mcpServer.getName());
            if (existing != null && existing.getConfig().equals(mcpConfig)) {
                // 配置未变：复用现有实例（不重建连接，避免旧客户端泄漏），状态沿用上次回显
                tmpNew.add(mcpConfig);
                continue;
            }
            BaseMcp baseMcp = null;
            try {
                baseMcp = BaseMcp.of(mcpConfig);
            } catch (IllegalArgumentException e) {
                LOG.warn("failed to load MCP config", e);
            }
            if (baseMcp != null) {
                if (existing != null) {
                    // 同名但配置变了：先摘旧实例的工具并关闭旧连接，再换新
                    unregisterAndClose(existing);
                }
                mcpPools.put(mcpServer.getName(), baseMcp);
                tmpNew.add(mcpConfig);
                currentState.put(mcpServer.getName(), new McpCatalog.ServerRuntime(mcpServer.getName(), "connecting", "", List.of()));
            } else if (existing != null) {
                // 新配置非法但旧的还挂着：旧配置已不被认可，照常摘除（占住名额防被 diff 误判）
                unregisterAndClose(existing);
                mcpPools.remove(mcpServer.getName());
                currentState.remove(mcpServer.getName());
                tmpNew.add(mcpConfig);
            }
        }
        Set<McpConfig> tmpCurrent = mcpPools.values().stream().collect(HashSet::new, (set, mcp) -> set.add(mcp.getConfig()), Set::addAll);
        tmpCurrent.removeAll(tmpNew);
        removeUnavailable(tmpCurrent);
        registerToToolManager();
    }

    private static void removeUnavailable(Set<McpConfig> tmpCurrent) {
        Set<String> unavailableServers = tmpCurrent.stream().collect(HashSet::new, (set, config) -> set.add(config.name()), Set::addAll);
        for (String unavailableName : unavailableServers) {
            BaseMcp removed = mcpPools.remove(unavailableName);
            if (removed != null) {
                unregisterAndClose(removed);
            }
            currentState.remove(unavailableName);
        }
    }

    /** 摘除一个 server 实例的全部工具并关闭底层连接；每步独立吞错，清理链不因单步失败中断（漏一个比断整个清理好） */
    private static void unregisterAndClose(BaseMcp mcp) {
        try {
            for (McpTool unavailableTool : mcp.listTools(false)) {
                ToolManager.unregister(unavailableTool);
            }
        } catch (Throwable e) {
            LOG.warn("failed to unregister tools of mcp server {}", mcp.getConfig().name(), e);
        }
        try {
            mcp.closeGracefully();
        } catch (Throwable e) {
            LOG.warn("failed to close mcp server {}", mcp.getConfig().name(), e);
        }
    }

    private static void registerToToolManager() {
        mcpPools.values().forEach(mcp -> {
            List<McpTool> mcpTools = null;
            String state = "connected";
            String detail = "";
            try {
                mcpTools = mcp.listTools(true);
                for (McpTool mcpTool : mcpTools) {
                    ToolManager.register(mcpTool);
                }

            } catch (Throwable e) {
                LOG.warn("failed to register MCP tools", e);
                state = "error";
                detail = e.getMessage();
            }
            Collection<? extends McpCatalog.McpToolInfo> finalMcpTools;
            if (mcpTools != null) {
                finalMcpTools = mcpTools.stream()
                        .collect(ArrayList::new, (col, tool) ->
                                col.add(new McpCatalog.McpToolInfo(tool.name(), tool.description())), ArrayList::addAll);
            } else {
                finalMcpTools = new ArrayList<>();
            }
            String finalState = state;
            String finalDetail = detail;
            currentState.computeIfPresent(mcp.getConfig().name(), (name, oldValue) ->
                    new McpCatalog.ServerRuntime(oldValue.name(), finalState, finalDetail, List.copyOf(finalMcpTools)));
        });
    }

    private static McpConfig convertMcpServerToMcpConfig(McpServer mcpServer) {
        String name = mcpServer.getName();
        McpConfig.Transport transport = null;
        switch (mcpServer.getTransport()) {
            case "http" ->  transport = McpConfig.Transport.HTTP;
            case "stdio" ->  transport = McpConfig.Transport.STDIO;
        }
        McpConfig.Protocol protocol = null;
        switch (mcpServer.getProtocol()) {
            case "auto" ->  protocol = McpConfig.Protocol.AUTO;
            case "legacy" ->  protocol = McpConfig.Protocol.LEGACY;
            case "modern" ->  protocol = McpConfig.Protocol.MODERN;
        }

        McpConfig.StdioConfig stdioConfig = new McpConfig.StdioConfig(mcpServer.getCommand(), mcpServer.getArgs(), mcpServer.getEnv());
        McpConfig.HttpConfig httpConfig = new McpConfig.HttpConfig(mcpServer.getUrl(), mcpServer.getHeaders());
        return new McpConfig(name, transport, protocol, stdioConfig, httpConfig);
    }

    private static void setCallBack() {
        McpCatalog.setStatusSource(() -> List.copyOf(currentState.values()));
    }

    public static void cleanup() {
        mcpPools.values().forEach(BaseMcp::closeGracefully);
        mcpPools.clear();
    }
}

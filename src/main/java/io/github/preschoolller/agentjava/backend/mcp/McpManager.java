package io.github.preschoolller.agentjava.backend.mcp;

import io.github.preschoolller.agentjava.backend.mcp.interfaces.BaseMcp;
import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import io.github.preschoolller.agentjava.frontend.store.McpCatalog;
import io.github.preschoolller.agentjava.frontend.store.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class McpManager {

    private static final ConcurrentHashMap<String, BaseMcp> mcpPools = new ConcurrentHashMap<>();
    private static final AppSettings appSettings = AppSettings.get();
    private static final Logger LOG = LoggerFactory.getLogger(McpManager.class);

    static {
        appSettings.addChangeListener(McpManager::loadMcpTools);
    }

    private McpManager() {
    }

    public static void loadMcpTools() {
        List<McpServer> mcpServers = appSettings.getMcpServers();
        for (McpServer mcpServer : mcpServers) {
            McpConfig mcpConfig = convertMcpServerToMcpConfig(mcpServer);
            try {
                BaseMcp baseMcp = BaseMcp.of(mcpConfig);
                mcpPools.put(mcpServer.getName(), baseMcp);
            } catch (IllegalArgumentException e) {
                LOG.warn("failed to load MCP config", e);
            }
        }
        registerToToolManager();
    }

    private static void registerToToolManager() {
        mcpPools.values().forEach(mcp -> {
            try {
                List<McpTool> mcpTools = mcp.listTools(true);
                for (McpTool mcpTool : mcpTools) {
                    ToolManager.register(mcpTool);
                }
            } catch (Throwable e) {
                LOG.warn("failed to register MCP tools", e);
            }
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
        McpCatalog.setStatusSource(new McpCatalog.StatusSource() {

            @Override
            public List<McpCatalog.ServerRuntime> current() {
                return List.of();
            }
        });
    }
}

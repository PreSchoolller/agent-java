package io.github.preschoolller.agentjava.backend.mcp;

import io.github.preschoolller.agentjava.backend.mcp.interfaces.BaseMcp;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.client.McpClient;
import io.modelcontextprotocol.modern.client.McpSyncClient;
import io.modelcontextprotocol.modern.client.transport.HttpClientMcpTransport;
import io.modelcontextprotocol.modern.client.transport.McpClientTransport;
import io.modelcontextprotocol.modern.client.transport.McpHttpRequestCustomizer;
import io.modelcontextprotocol.modern.client.transport.StdioMcpClientTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ModernMcp implements BaseMcp {

    private final McpSyncClient client;
    private volatile List<McpTool> toolList;
    private final McpConfig config;

    public ModernMcp(McpConfig config) {
        check(config);
        McpClientTransport mcpClientTransport = null;
        switch (config.transport()) {
            case HTTP -> {
                McpHttpRequestCustomizer requestCustomizer = McpHttpRequestCustomizer
                        .fromSync((request, headers, context) ->
                                config.httpConfig().headers().forEach(headers::set)
                        );
                mcpClientTransport = HttpClientMcpTransport
                        .builder(config.httpConfig().url())
                        .requestCustomizer(requestCustomizer)
                        .build();
            }
            case STDIO -> {
                ServerParameters.Builder serverParametersBuilder = ServerParameters
                        .builder(config.stdioConfig().command())
                        .args(config.stdioConfig().args());
                config.stdioConfig().env().forEach(serverParametersBuilder::addEnvVar);
                mcpClientTransport = StdioMcpClientTransport
                        .builder(serverParametersBuilder.build())
                        .start();

            }
            default -> throw new IllegalArgumentException("Unsupported transport type: " + config.transport());
        }
        client = McpClient
                .sync(mcpClientTransport)
                .roundTimeout(Duration.ofSeconds(30))
                .build();
        this.config = config;
    }

    private static void check(McpConfig config) {
        if (config == null || config.transport() == null || config.protocol() == null) {
            throw new IllegalArgumentException("Invalid configuration");
        }
        if (config.name() == null || config.name().isBlank()) {
            throw new IllegalArgumentException("Invalid configuration");
        }
        if (McpConfig.Protocol.LEGACY.equals(config.protocol())) {
            throw new IllegalArgumentException("Invalid configuration");
        }
        switch (config.transport()) {
            case HTTP -> {
                if (config.httpConfig() == null || config.httpConfig().url() == null) {
                    throw new IllegalArgumentException("Invalid configuration");
                }
            }
            case STDIO -> {
                if (config.stdioConfig() == null || config.stdioConfig().command() == null || config.stdioConfig().command().isBlank()) {
                    throw new IllegalArgumentException("Invalid configuration");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported transport type: " + config.transport());
        }
    }

    @Override
    public List<McpTool> listTools(boolean reFetch) {
        if (reFetch) {
            synchronized (this) {
                toolList = fetchToolList();
            }
        } else if (toolList == null) {
            synchronized (this) {
                if (toolList == null) {
                    toolList = fetchToolList();
                }
            }
        }
        return Collections.unmodifiableList(toolList);
    }

    private List<McpTool> fetchToolList() {
        List<McpTool> result = new ArrayList<>();
        client.listTools().skipCache().list().tools().forEach(tool -> result.add(new McpTool(config.name(), tool.name()) {
            @Override
            public String description() {
                return tool.description();
            }

            @Override
            public Map<String, Object> schema() {
                return tool.inputSchema();
            }

            @Override
            public String execute(String arguments, String workspace) {
                return ModernMcp.this.execute(tool.name(), parseArguments(arguments));
            }
        }));
        return result;
    }

    private String execute(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult callToolResult = client.tool(toolName).arguments(arguments).call();
        StringBuilder result = new StringBuilder();
        if (callToolResult.isError()) {
            result.append("mcp tool call returned error: ");
        }
        result.append(callToolResult.content());
        return result.toString();
    }

    @Override
    public void initialize() {
    }

    @Override
    public Object ping() {
        return null;
    }

    @Override
    public void closeGracefully() {
    }
}

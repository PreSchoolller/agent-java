package io.github.preschoolller.agentjava.backend.mcp;

import io.github.preschoolller.agentjava.backend.mcp.interfaces.BaseMcp;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import okhttp3.HttpUrl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class LegacyMcp implements BaseMcp {

    private final McpSyncClient client;
    private volatile List<McpTool> toolList;
    private final McpConfig config;

    public LegacyMcp(McpConfig config) {
        check(config);
        McpClientTransport mcpClientTransport = null;
        switch (config.transport()) {
            case HTTP -> {
                String url = config.httpConfig().url();
                HttpUrl parsed = HttpUrl.parse(url);
                if (parsed == null) {
                    throw new IllegalArgumentException("failed to parse url: " + url);
                }
                // URL 无显式端口时 port() 返回 -1，不能拼进 baseUri（默认端口由 scheme 决定）
                int port = parsed.port();
                String baseUri = parsed.scheme() + "://" + parsed.host() + (port != -1 ? ":" + port : "");
                String endpoint = parsed.encodedPath();
                if (endpoint.equals("/") || endpoint.isBlank()) {
                    endpoint = "/mcp";
                }
                mcpClientTransport = HttpClientStreamableHttpTransport
                        .builder(baseUri)
                        .endpoint(endpoint)
                        .build();
            }
            case STDIO -> {
                McpConfig.StdioConfig stdioConfig = config.stdioConfig();
                ServerParameters.Builder serverParametersBuilder = ServerParameters.builder(stdioConfig.command());
                serverParametersBuilder.args(stdioConfig.args());
                stdioConfig.env().forEach(serverParametersBuilder::addEnvVar);
                mcpClientTransport = new StdioClientTransport(serverParametersBuilder.build(), McpJsonDefaults.getMapper());
            }
            default -> throw new IllegalArgumentException("Unsupported transport type: " + config.transport());
        }
        client = McpClient
                .sync(mcpClientTransport)
                .requestTimeout(Duration.ofSeconds(30))
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
        if (McpConfig.Protocol.MODERN.equals(config.protocol())) {
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
    public void initialize() {
        client.initialize();
    }

    @Override
    public Object ping() {
        return client.ping();
    }

    @Override
    public void closeGracefully() {
        client.closeGracefully();
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

    @Override
    public McpConfig getConfig() {
        return config;
    }

    private List<McpTool> fetchToolList() {
        List<McpTool> result = new ArrayList<>();
        client.listTools().tools().forEach(tool -> result.add(new McpTool(config.name(), tool.name()) {
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
                return LegacyMcp.this.execute(tool.name(), parseArguments(arguments));
            }
        }));
        return result;
    }

    private String execute(String name, Map<String, Object> arguments) {
        McpSchema.CallToolRequest.Builder builder = McpSchema.CallToolRequest.builder(name);
        builder.arguments(arguments);
        McpSchema.CallToolResult callToolResult = client.callTool(builder.build());
        String body = contentToText(callToolResult.content());
        return callToolResult.isError() ? "mcp tool call returned error: " + body : body;
    }

    /** 把 MCP 返回的 content 块拼成模型可读文本：TextContent 取 text，其余块（image/resource）留类型占位 */
    private static String contentToText(List<McpSchema.Content> contents) {
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : contents) {
            if (content instanceof McpSchema.TextContent textContent) {
                text.append(textContent.text());
            } else {
                text.append('[').append(content.type()).append(']');
            }
            text.append('\n');
        }
        return text.toString().strip();
    }
}

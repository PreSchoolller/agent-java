package io.github.preschoolller.agentjava.backend.mcp.interfaces;

import io.github.preschoolller.agentjava.backend.mcp.LegacyMcp;
import io.github.preschoolller.agentjava.backend.mcp.McpConfig;
import io.github.preschoolller.agentjava.backend.mcp.McpTool;
import io.github.preschoolller.agentjava.backend.mcp.ModernMcp;

import java.util.List;

public interface BaseMcp {

    static BaseMcp of(McpConfig config) {
        if (config != null && McpConfig.Protocol.MODERN.equals(config.protocol())) {
            return ofModern(config);
        }
        return ofLegacy(config);
    }

    static BaseMcp ofModern(McpConfig config) {
        return new ModernMcp(config);
    }

    static BaseMcp ofLegacy(McpConfig config) {
        return new LegacyMcp(config);
    }

    default void initialize() {}

    Object ping();

    default void closeGracefully() {}

    List<McpTool> listTools(boolean reFetch);
}

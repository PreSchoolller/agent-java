package io.github.preschoolller.agentjava.backend.mcp;

import java.util.List;
import java.util.Map;

public record McpConfig(String name, Transport transport, Protocol protocol,
                        StdioConfig stdioConfig, HttpConfig httpConfig) {

    public enum Transport {
        STDIO,
        HTTP,
    }

    public enum Protocol {
        AUTO,
        LEGACY,
        MODERN
    }

    public record StdioConfig(String command, List<String> args, Map<String, String> env) {
    }

    public record HttpConfig(String url, Map<String, String> headers) {
    }
}

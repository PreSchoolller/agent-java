package io.github.preschoolller.agentjava.backend.mcp;

import java.util.List;
import java.util.Map;

public record McpConfig(String name, Transport transport, Protocol protocol,
                        StdioConfig stdioConfig, HttpConfig httpConfig) {

    enum Transport {
        STDIO,
        HTTP,
    }

    enum Protocol {
        AUTO,
        LEGACY,
        MODERN
    }

    record StdioConfig(String command, List<String> args, Map<String, String> env) {
    }

    record HttpConfig(String url, Map<String, String> headers) {
    }
}

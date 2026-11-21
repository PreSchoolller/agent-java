package io.github.preschoolller.agentjava.spi.tool.records;

import java.util.Map;

public record ToolInfo(String name, String description, Map<String, Object> schema, boolean strictMode) {
}

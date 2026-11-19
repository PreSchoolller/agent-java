package io.github.preschoolller.agentjava.backend.mcp;

import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public abstract class McpTool implements Tool {
    private final String originalName;
    private final String serverName;
    private final Logger logger;

    public McpTool(String serverName, String originalName) {
        this.serverName = serverName;
        this.originalName = originalName;
        logger = LoggerFactory.getLogger(this.getClass());
    }

    @Override
    public final String name() {
        return "mcp__" + serverName.toLowerCase() + "__" + originalName;
    }

    @Override
    public abstract String execute(String arguments, String workspace);

    Map<String, Object> parseArguments(String arguments) {
        Map<String, Object> result = new HashMap<>();
        try {
            result = Constants.getObjectMapper().readValue(arguments, Map.class);
        } catch (Throwable e) {
            logger.error("failed to parse arguments {} for mcp tool {} call: ", arguments, name(), e);
        }
        return result;
    }

    @Override
    public final boolean strictMode() {
        return false;
    }
}

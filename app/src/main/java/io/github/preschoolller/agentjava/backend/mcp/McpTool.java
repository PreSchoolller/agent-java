package io.github.preschoolller.agentjava.backend.mcp;

import io.github.preschoolller.agentjava.spi.common.Constants;
import io.github.preschoolller.agentjava.spi.tool.Tool;
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
        return "mcp__" + sanitize(serverName) + "__" + sanitize(originalName);
    }

    /** 注册名落到 provider 的函数命名契约（字母/数字/下划线/连字符）：怪字符换下划线，超长截断；
     *  纯函数保证同一输入映射稳定，unregister 按 name() 摘除时与注册名恒一致 */
    private static String sanitize(String raw) {
        String cleaned = (raw == null ? "" : raw.strip()).toLowerCase();
        cleaned = cleaned.replaceAll("[^a-z0-9_-]", "_");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
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

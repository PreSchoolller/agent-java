package io.github.preschoolller.agentjava.spi.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

public class Constants {
    public static ObjectMapper getObjectMapper() {
        return InstanceHolder.MAPPER;
    }

    public static final Path APP_USER_DIR = Path.of(System.getProperty("user.home"), ".agentjava");

    public static final Path DEFAULT_WORKSPACE_DIR = APP_USER_DIR.resolve("workspace");

    public static final Path TEMP_DIR = Path.of(System.getProperty("java.io.tmpdir"), ".agentjava");

    public static final Path PLUGIN_DIR = APP_USER_DIR.resolve("plugins");

    private static class InstanceHolder {
        private static final ObjectMapper MAPPER = new ObjectMapper();
    }

    public static final String DENIED_RESULT = "用户拒绝了本次工具调用，工具未执行。请不要重试同一调用，向用户说明后等待指示。";
}

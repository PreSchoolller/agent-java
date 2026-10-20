package io.github.preschoolller.agentjava.backend.constant;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

public class Constants {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Path APP_USER_DIR = Path.of(System.getProperty("user.home"), ".agentjava");

    public static final Path DEFAULT_WORKSPACE_DIR = APP_USER_DIR.resolve("workspace");
}

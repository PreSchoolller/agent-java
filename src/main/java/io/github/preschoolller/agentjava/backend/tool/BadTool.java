package io.github.preschoolller.agentjava.backend.tool;

import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.Map;

public class BadTool implements Tool {

    private static volatile BadTool instance;

    private BadTool() {
    }

    public static BadTool getInstance() {
        if (instance == null) {
            synchronized (BadTool.class) {
                if (instance == null) {
                    instance = new BadTool();
                }
            }
        }
        return instance;
    }

    @Override
    public String name() {
        return null;
    }

    @Override
    public String description() {
        return null;
    }

    @Override
    public Map<String, Object> schema() {
        return null;
    }

    @Override
    public String execute(String arguments) {
        return "unknown tool";
    }
}

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
    public boolean readOnly() {
        return true; // 占位工具：什么都不做，只回一句错误，没必要为它弹确认
    }

    @Override
    public String execute(String arguments) {
        // 这句话会作为工具结果回给模型：说清楚是名字没对上，模型才有机会纠正
        return "unknown function call: no registered tool with this name";
    }
}

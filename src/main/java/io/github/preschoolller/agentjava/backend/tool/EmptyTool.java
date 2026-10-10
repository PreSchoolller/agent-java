package io.github.preschoolller.agentjava.backend.tool;

import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.Map;

/**
 * 占位
 */
public class EmptyTool implements Tool {

    private static volatile EmptyTool instance;

    private EmptyTool() {
    }

    public static EmptyTool getInstance() {
        if (instance == null) {
            synchronized (EmptyTool.class) {
                if (instance == null) {
                    instance = new EmptyTool();
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
        return true; // 占位工具：执行体是空的，不参与权限判断
    }

    @Override
    public String execute(String arguments) {
        return null;
    }
}

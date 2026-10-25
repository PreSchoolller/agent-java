package io.github.preschoolller.agentjava.plugin;

import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.Map;

/**
 * 文件编辑工具，放在插件里是要学习服务加载
 */
public class ToolEdit implements Tool {

    public ToolEdit() {
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "编辑文件";
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
    public String execute(String arguments, String workspace) {
        return null;
    }
}

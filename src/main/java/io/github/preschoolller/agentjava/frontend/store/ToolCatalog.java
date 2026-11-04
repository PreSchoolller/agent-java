package io.github.preschoolller.agentjava.frontend.store;

import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具注册表读桥：直接读 ToolManager 注册表的 name()/description()。
 */
public final class ToolCatalog {

    /** 注册工具的一条展示信息：name 供开关存取，description 给勾选行当说明文字 */
    public record ToolInfo(String name, String description) {}

    private ToolCatalog() {
    }

    /** 当前注册的全部工具信息（防御性拷贝；类加载/初始化失败退空列表） */
    public static List<ToolInfo> registered() {
        List<ToolInfo> infos = new ArrayList<>();
        try {
            // 读完字段快照再遍历：tools 是运行期可 register() 增长的活列表，防遍历中并发改
            for (Tool tool : new ArrayList<>(ToolManager.tools.values())) {
                infos.add(new ToolInfo(tool.name(), tool.description()));
            }
        } catch (Throwable ignored) {
            // 裸类路径回归（ToolManager.<clinit> 触发 ToolRead/ToolBash 的 NCDFE，踩坑 #26）：
            // 按"没拿到工具列表"兜底，未配置时的"全部启用"语义不受影响
        }
        return List.copyOf(infos);
    }
}

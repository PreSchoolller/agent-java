package io.github.preschoolller.agentjava.frontend.store;

import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具注册表读桥：直接读 ToolManager 注册表的 name()/description()。
 * 编译期依赖没问题（mvn 全量编译有 Jackson）；坑在**运行环境**——ToolRead/ToolBash 的方法
 * 签名带 throws JsonProcessingException（类加载校验就要解析这个类型），裸类路径回归（Shot 文档
 * 命令，踩坑 #26）下 ToolManager 一初始化就 NoClassDefFoundError。所以整个读取包在
 * catch(Throwable) 里失败退空列表：工具页显示"未拿到工具列表"占位（拿不到就不进勾选行，
 * 保存时保持原配置不动），真应用/IDE 全量 classpath 下正常出注册表行。
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
            for (Tool tool : new ArrayList<>(ToolManager.tools)) {
                infos.add(new ToolInfo(tool.name(), tool.description()));
            }
        } catch (Throwable ignored) {
            // 裸类路径回归（ToolManager.<clinit> 触发 ToolRead/ToolBash 的 NCDFE，踩坑 #26）：
            // 按"没拿到工具列表"兜底，未配置时的"全部启用"语义不受影响
        }
        return List.copyOf(infos);
    }

    /** 注册表里全部工具名（开关存名单用），语义同 registered() */
    public static List<String> registeredNames() {
        List<String> names = new ArrayList<>();
        for (ToolInfo info : registered()) {
            names.add(info.name());
        }
        return List.copyOf(names);
    }
}

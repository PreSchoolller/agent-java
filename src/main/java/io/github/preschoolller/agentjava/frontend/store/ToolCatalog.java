package io.github.preschoolller.agentjava.frontend.store;

import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具注册表读桥：读 ToolManager 注册表的 name()/description()，以及 ui 交互通道
 * （uiTips/uiInteractive/getPersistentInput，设置面板工具页的每工具可展开交互区消费）。
 */
public final class ToolCatalog {

    /** 注册工具的一条展示信息：name 供开关存取，description 给勾选行当说明文字；
     *  interactiveTips=uiTips 原文（非接口默认哨兵才有值，null=不支持交互，前端不渲染交互区） */
    public record ToolInfo(String name, String description, String interactiveTips) {}

    private ToolCatalog() {
    }

    /** 当前注册的全部工具信息（防御性拷贝；类加载/初始化失败退空列表） */
    public static List<ToolInfo> registered() {
        List<ToolInfo> infos = new ArrayList<>();
        try {
            // 读完字段快照再遍历：tools 是运行期可 register() 增长的活列表，防遍历中并发改
            for (Tool tool : new ArrayList<>(ToolManager.tools.values())) {
                String name;
                String description;
                String tips;
                try {
                    name = tool.name();
                    description = tool.description();
                    String rawTips = tool.uiTips();
                    // 接口默认哨兵=没实现交互；null 让前端不渲染交互控件
                    tips = "not implemented".equals(rawTips) ? null : rawTips;
                } catch (Throwable ignored) {
                    // 单个工具自省失败不连坐整个注册表（一个坏 description 别把后面工具全藏了）
                    continue;
                }
                infos.add(new ToolInfo(name, description, tips));
            }
        } catch (Throwable ignored) {
            // 裸类路径回归（ToolManager.<clinit> 触发 ToolRead/ToolBash 的 NCDFE，踩坑 #26）：
            // 按"没拿到工具列表"兜底，未配置时的"全部启用"语义不受影响
        }
        return List.copyOf(infos);
    }

    /** 读工具的持久化交互输入（Tool.getPersistentInput 读桥，前端预填交互区输入框用）：
     *  null=未配置或工具不可用（裸类路径回归等，踩坑 #26 同源，兜底不抛） */
    public static String persistentInput(String toolName) {
        try {
            Tool tool = ToolManager.tools.get(toolName);
            return tool == null ? null : tool.getPersistentInput();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 执行工具的交互（Tool.uiInteractive 写桥）：把用户输入交给工具自行解释并应用，
     * 返回工具给的反馈文本；工具不可用/调用失败退 null（前端按"未执行成功"处理，不落盘）。
     * 可能拉起进程（如 bash 探测最坏 10 秒），调用方必须在后台线程跑，不能占 EDT。
     */
    public static String uiInteractive(String toolName, String input) {
        try {
            Tool tool = ToolManager.tools.get(toolName);
            return tool == null ? null : tool.uiInteractive(input);
        } catch (Throwable t) {
            return null;
        }
    }
}

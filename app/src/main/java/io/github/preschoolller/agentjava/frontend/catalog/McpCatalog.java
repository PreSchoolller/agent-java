package io.github.preschoolller.agentjava.frontend.catalog;

import java.util.List;

/**
 * MCP 运行态读桥（设置面板「MCP」页展示每个 server 的连接状态与已发现的工具）。
 * 配置本体在 RuntimeConfig.mcpServers()（前端编辑、后端消费），这里只放"后端跑起来之后"
 * 的运行态。后端实现 MCP Client 后调 {@link #setStatusSource} 注册数据源；页面构建/重建时
 * 拉一次快照（不追实时推送，后端重连/发现变化后由前端下次打开面板或重建页时取到）。
 * 未注册数据源（后端未接入）或裸类路径回归时退空列表，页面显示"未运行"兜底
 * （ToolCatalog 同款防御思路，踩坑 #26）。
 */
public final class McpCatalog {

    /** 已发现工具的展示信息：name=注册进 ToolManager 的全名（mcp__&lt;服务名&gt;__&lt;工具名&gt;），
     *  description=模型看到的说明（页面悬停/列表说明文字用） */
    public record McpToolInfo(String name, String description) {}

    /** 一个 server 的运行态快照：state 取 stopped / connecting / connected / error；
     *  detail 给错误摘要或补充说明（页面原样展示）；tools=该 server 发现的工具 */
    public record ServerRuntime(String name, String state, String detail, List<McpToolInfo> tools) {}

    /** 后端实现的数据源：每次调用现查现回（快照语义，调用方在后台线程外也应能安全拉取） */
    public interface StatusSource {
        List<ServerRuntime> current();
    }

    private static volatile StatusSource statusSource;

    private McpCatalog() {
    }

    /** 后端 MCP Client 就绪后注册（传 null 注销）；重复注册以最后一次为准 */
    public static void setStatusSource(StatusSource source) {
        statusSource = source;
    }

    /** 当前全部 server 的运行态快照；后端未接入/数据源炸了退空列表（页面兜底"未运行"） */
    public static List<ServerRuntime> runtime() {
        try {
            StatusSource source = statusSource;
            if (source == null) {
                return List.of();
            }
            List<ServerRuntime> list = source.current();
            return list == null ? List.of() : List.copyOf(list);
        } catch (Throwable ignored) {
            return List.of();
        }
    }
}

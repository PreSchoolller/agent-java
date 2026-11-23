package io.github.preschoolller.agentjava.core.records;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置条目的投影（设置面板「MCP」页的可编辑形态）。
 * {@code transport}：stdio / http；{@code protocol}：legacy（握手）/ modern（无握手）。
 * 连接状态与已发现工具不在这里，走 McpCatalog 的写桥回显。
 *
 * <p>{@code headers} / {@code env} 里承载授权的值（{@code Authorization}、token、API key 等）
 * 是写侧字段：前端保存时传入，读取时投影不带出（该键缺失）；传 null 表示
 * 「不更改已落库的值」，不是「清空」——与 {@link ProfileView#key()} 同一条规则。
 */
public record McpServerView(String name, String transport, String protocol, boolean enabled,
                            String command, List<String> args, Map<String, String> env,
                            String url, Map<String, String> headers) {
}
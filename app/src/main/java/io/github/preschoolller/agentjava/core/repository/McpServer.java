package io.github.preschoolller.agentjava.core.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置条目（设置面板「MCP」页编辑，AppSettings 以 mcpServers 键整块 JSON 落盘）。
 * 一个条目 = 一个 MCP server 连接定义：stdio（本机子进程：命令+参数+环境变量）
 * 或 http（Streamable HTTP 端点：地址+请求头）。
 * 后端契约（MCP 支持规划阶段 0 定下）：
 * - 后端经 AppSettings.getMcpServers() 读名单，自行建连/initialize/tools-list；
 * - 发现的工具注册进 ToolManager 时用 mcp__&lt;服务名&gt;__&lt;工具名&gt; 前缀，
 *   避免无意触发"插件覆盖同名内置工具"的语义；
 * - 连接状态与已发现工具经 McpCatalog 的 StatusSource 写桥回给前端展示。
 * 前端 catalog 不 import backend 类（踩坑 #26 的连带加载雷），契约只靠 AppSettings/McpCatalog 两个面。
 */
public final class McpServer {

    public static final String TRANSPORT_STDIO = "stdio";
    public static final String TRANSPORT_HTTP = "http";
    /** 传输方式取值（设置页下拉与落盘值一一对应） */
    public static final List<String> TRANSPORTS = List.of(TRANSPORT_STDIO, TRANSPORT_HTTP);

    /** 协议代=legacy：必须 initialize 握手建会话（2025-06-18 一代；IDEA 内置 server 是这类） */
    public static final String PROTOCOL_LEGACY = "legacy";
    /** 协议代=modern：无握手，直接业务请求（2026-07-28 新协议，官方 SDK modern 包实现） */
    public static final String PROTOCOL_MODERN = "modern";
    /** 协议代取值（设置页下拉与落盘值一一对应；缺键=legacy，非法落 legacy） */
    public static final List<String> PROTOCOLS = List.of(PROTOCOL_LEGACY, PROTOCOL_MODERN);

    /** 服务名：用户起、唯一（保存时前端校验）；工具注册前缀与状态路由都用它 */
    private String name = "";
    /** 传输方式：stdio / http */
    private String transport = TRANSPORT_HTTP;
    /** 协议代：legacy（握手，现主流）/ modern（无握手）；建连失败时显式换一档是用户的排查口 */
    private String protocol = PROTOCOL_LEGACY;
    /** 启用开关：关闭=后端不建连（配置保留） */
    private boolean enabled = true;
    /** stdio：可执行文件（命令本身） */
    private String command = "";
    /** stdio：命令参数列表（每项一个参数；UI 上逗号分隔编辑） */
    private List<String> args = List.of();
    /** stdio：子进程环境变量（UI 上 KEY=VALUE 逗号分隔编辑） */
    private Map<String, String> env = new LinkedHashMap<>();
    /** http：Streamable HTTP 端点完整地址 */
    private String url = "";
    /** http：随请求发送的自定义请求头（UI 上 KEY=VALUE 逗号分隔编辑；
     *  IDEA 多项目同开时用 IJ_MCP_SERVER_PROJECT_PATH=&lt;项目路径&gt; 路由） */
    private Map<String, String> headers = new LinkedHashMap<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name.strip();
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = TRANSPORTS.contains(transport) ? transport : TRANSPORT_HTTP;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = PROTOCOLS.contains(protocol) ? protocol : PROTOCOL_LEGACY;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command == null ? "" : command.strip();
    }

    /** 参数列表（防御性拷贝；null/空白项过滤） */
    public List<String> getArgs() {
        return List.copyOf(args);
    }

    public void setArgs(List<String> args) {
        if (args == null) {
            this.args = List.of();
            return;
        }
        this.args = args.stream().filter(a -> a != null && !a.isBlank()).map(String::strip).toList();
    }

    public Map<String, String> getEnv() {
        return new LinkedHashMap<>(env);
    }

    public void setEnv(Map<String, String> env) {
        this.env = cleanPairs(env);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url.strip();
    }

    public Map<String, String> getHeaders() {
        return new LinkedHashMap<>(headers);
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = cleanPairs(headers);
    }

    /** k=v 表清洗：丢 null 键值，键去空白；顺序保持（LinkedHashMap） */
    private static Map<String, String> cleanPairs(Map<String, String> pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        if (pairs != null) {
            for (Map.Entry<String, String> entry : pairs.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(entry.getKey().strip(), entry.getValue().strip());
                }
            }
        }
        return result;
    }

    /** 深拷贝（设置面板工作副本用；对象引用不相交，改副本不脏真配置） */
    public McpServer copy() {
        McpServer clone = new McpServer();
        clone.setName(name);
        clone.setTransport(transport);
        clone.setProtocol(protocol);
        clone.setEnabled(enabled);
        clone.setCommand(command);
        clone.setArgs(args);
        clone.setEnv(new LinkedHashMap<>(env));
        clone.setUrl(url);
        clone.setHeaders(new LinkedHashMap<>(headers));
        return clone;
    }

    /** 序列化成 JSON Map（AppSettings.save 经 JsonKit.write 整块落盘） */
    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", name);
        json.put("transport", transport);
        json.put("protocol", protocol);
        json.put("enabled", enabled);
        json.put("command", command);
        json.put("args", new ArrayList<>(args));
        json.put("env", new LinkedHashMap<>(env));
        json.put("url", url);
        json.put("headers", new LinkedHashMap<>(headers));
        return json;
    }

    /**
     * 反序列化（load 时逐条恢复）。宽容解析：字段缺失/类型不对都落默认值，
     * 不让一个坏条目炸掉整个设置加载；名字空白由调用方决定丢弃。
     */
    public static McpServer fromJson(Map<String, Object> json) {
        McpServer server = new McpServer();
        if (json == null) {
            return server;
        }
        Object name = json.get("name");
        if (name instanceof String s) {
            server.setName(s);
        }
        Object transport = json.get("transport");
        if (transport instanceof String s) {
            server.setTransport(s);
        }
        Object protocol = json.get("protocol");
        if (protocol instanceof String s) {
            server.setProtocol(s); // 缺键保持 legacy（现主流），非法值落 legacy
        }
        Object enabled = json.get("enabled");
        if (enabled instanceof Boolean b) {
            server.setEnabled(b);
        }
        Object command = json.get("command");
        if (command instanceof String s) {
            server.setCommand(s);
        }
        Object args = json.get("args");
        if (args instanceof List<?> list) {
            List<String> parsed = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) {
                    parsed.add(s);
                }
            }
            server.setArgs(parsed);
        }
        server.setEnv(pairsFromJson(json.get("env")));
        Object url = json.get("url");
        if (url instanceof String s) {
            server.setUrl(s);
        }
        server.setHeaders(pairsFromJson(json.get("headers")));
        return server;
    }

    /** k=v 表的宽容反序列化：值非 String 的条目丢弃 */
    private static Map<String, String> pairsFromJson(Object raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                    result.put(key, value);
                }
            }
        }
        return result;
    }
}

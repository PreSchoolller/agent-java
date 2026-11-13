package io.github.preschoolller.agentjava.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * bili 写工具：会真的对 B 站产生副作用的命令（评论/私信/动态/弹幕/收藏/通知）。
 *
 * 安全设计（对齐 bili.mjs 自身的强制约定「写操作必须显式 --dry 或 --go」）：
 *  - 模型参数里的 --go 一律剥掉，--go 只能由本工具根据 go 布尔参数追加——
 *    模型想绕过调用方的确认，没有这条路；
 *  - go=false（默认）时，对需要显式标志的命令自动补 --dry（纯预演，不发请求体）；
 *  - readOnly=false：EDIT 权限模式下首轮就会弹确认卡，PLAN 全拦，FULL_ACCESS 直通
 *    （与 bash 工具同一待遇，不加额外审批，尊重完全访问的契约）。
 */
public class ToolBiliWrite implements Tool {

    static final String COMMAND = "command";
    static final String ARGS = "args";
    static final String GO = "go";
    static final String TIMEOUT = "timeout_sec";

    private static final String DRY_FLAG = "--dry";
    private static final String GO_FLAG = "--go";

    /**
     * 会真实对外发出内容的命令。value = 一句话用途。
     * 注意：danmaku 的 pre/history 子命令是只读的，但也放在这里——本工具不给只读用途。
     */
    static final Map<String, String> WRITE_COMMANDS = Map.ofEntries(
            Map.entry("comment", "发视频评论（带 --root/--parent 为二级回复）"),
            Map.entry("comment-del", "删除自己发的一条评论"),
            Map.entry("dm", "私信专属通道（只发主人、必须互关）；--status 看额度"),
            Map.entry("reply-draft", "待审回复队列：add / list / drop / take"),
            Map.entry("fav", "收藏（判据强制）"),
            Map.entry("dynamic", "动态：post / rm / check"),
            Map.entry("dynamic-image", "发图文动态（上传图片+可带 @ 节点）"),
            Map.entry("dynamic-edit", "编辑已存在的动态：<dyn_id> <文本文件>"),
            Map.entry("danmaku", "直播间弹幕：pre 预检 / send 发送 / history 回读 / live 实时流"),
            Map.entry("notify", "通知分发（走 config.notify 配置的渠道）")
    );

    /** 这些命令的工具链脚本强制要求显式 --dry 或 --go，缺省时本工具自动补 --dry */
    static final Set<String> NEEDS_FLAG = Set.of(
            "comment", "comment-del", "dm", "fav", "dynamic", "dynamic-image", "dynamic-edit", "danmaku");

    @Override
    public String name() {
        return "bili_write";
    }

    @Override
    public String description() {
        return """
                B 站写操作（发评论/私信/动态/弹幕/收藏/通知），会真实影响外部世界，克制使用。
                默认 go=false 只做 --dry 预演（打印将发出的请求，不真发）；
                确认无误后同参数 go=true 真正执行。发之前先用 bili 工具跑 session 确认是哪个号、
                recon 侦查评论区状态；写完后用 check-comment/thread 回读复核。
                红线：一次一条，不批量；不碰限额闸（quota）挡下来的操作。""";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        COMMAND, Map.of(
                                "type", "string",
                                "description", "子命令：" + commandsSummary(),
                                "enum", List.copyOf(WRITE_COMMANDS.keySet())),
                        ARGS, Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "参数列表（字符串数组）。不要包含 --dry/--go，由 go 参数控制"),
                        GO, Map.of(
                                "type", "boolean",
                                "description", "false（默认）= --dry 预演；true = --go 真发。真发前先 dry 看一遍"),
                        TIMEOUT, Map.of(
                                "type", "integer",
                                "description", "超时秒数，默认 120，上限 900")
                ),
                "required", List.of(COMMAND)
        );
    }

    static String commandsSummary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : WRITE_COMMANDS.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append(e.getKey()).append("（").append(e.getValue()).append("）");
        }
        return sb.toString();
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String execute(String arguments, String workspace) {
        JsonNode json;
        try {
            json = Constants.getObjectMapper().readTree(arguments);
        } catch (Exception e) {
            return "json parse error: " + e.getMessage();
        }
        if (json == null || !json.isObject()) {
            return "json parse error: wrong schema";
        }
        String command = json.path(COMMAND).asText(null);
        if (command == null || command.isBlank()) {
            return "缺 command 参数";
        }
        if (!WRITE_COMMANDS.containsKey(command)) {
            return "未知或非写命令：" + command + "。写命令见 schema enum；只读操作请用 bili 工具";
        }
        List<String> rawArgs = new ArrayList<>();
        JsonNode args = json.path(ARGS);
        if (args.isArray()) {
            ((ArrayNode) args).forEach(a -> rawArgs.add(a.asText()));
        }
        boolean go = json.path(GO).asBoolean(false);
        List<String> cmdArgs = sanitizeArgs(command, rawArgs, go);
        return BiliRunner.run(cmdArgs, BiliRunner.clampTimeout(
                json.has(TIMEOUT) && json.path(TIMEOUT).isInt() ? json.path(TIMEOUT).asInt() : null));
    }

    /**
     * 参数净化（包级可见供单测）：剥掉模型自带的 --go/--dry，按 go 参数重算。
     * 规则：go=true → 末尾追加 --go；否则 NEEDS_FLAG 命令且参数里没有 --dry → 补 --dry。
     */
    static List<String> sanitizeArgs(String command, List<String> rawArgs, boolean go) {
        List<String> out = new ArrayList<>();
        out.add(command);
        for (String a : rawArgs) {
            if (GO_FLAG.equals(a) || DRY_FLAG.equals(a)) {
                continue; // 模型无权决定真发与否
            }
            out.add(a);
        }
        if (go) {
            out.add(GO_FLAG);
        } else if (NEEDS_FLAG.contains(command) && !out.contains(DRY_FLAG)) {
            out.add(DRY_FLAG);
        }
        return out;
    }

    @Override
    public String uiTips() {
        return "工具链路径与 bili 工具共用（推荐在 bili 条目里配置；在这里填也认）";
    }

    @Override
    public String uiInteractive(String input) {
        return BiliLocator.uiInteractive(input);
    }
}

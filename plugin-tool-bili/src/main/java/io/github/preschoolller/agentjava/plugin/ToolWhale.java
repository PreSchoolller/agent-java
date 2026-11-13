package io.github.preschoolller.agentjava.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.List;
import java.util.Map;

/**
 * whale 工具：dsh-bili-whale 宿主侧（dsh-shim）的生命周期管理。
 *
 * 普通会话里一句"启动她"就能干活：start 拉起回环 API + shim 进程，原版生物钟/监听/
 * 人设全部开始工作（代码零修改复用，见 shim 资源头注释）。注入的回合落在会话
 * bili-whale，走与用户输入完全相同的 agent 链路和权限门。
 *
 * readOnly=false：start/stop 是有真实后果的生命周期操作（半夜会自己醒来干活），
 * EDIT 模式下弹确认卡是合理代价；status/log 顺带也被门住（工具级粒度，接受）。
 */
public class ToolWhale implements Tool {

    static final String COMMAND = "command";

    /** key = 子命令，value = 一句话用途（拼进 command 的 description） */
    static final Map<String, String> COMMANDS = Map.of(
            "start", "启动：拉起回环 API + dsh-shim，生物钟/监听/人设开始工作",
            "stop", "停止 shim 与回环 API",
            "status", "查看 shim 与回环 API 状态（不改动任何东西）",
            "log", "查看 shim 日志尾部（排障用）"
    );

    @Override
    public String name() {
        return "whale";
    }

    @Override
    public String description() {
        return """
                bili-whale 宿主侧（dsh-shim）生命周期。start 后原版生物钟按
                ~/.dsh/bili-whale.config.json 的夜窗口随机唤醒"她"——回合注入到会话
                bili-whale，走正常 agent 链路与权限门；私信/评论监听投递同时生效。
                使用节奏：start 一次 → status 确认 → 之后放着就行（应用托盘常驻）；
                出问题用 log 排查，不用了 stop。""";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        COMMAND, Map.of(
                                "type", "string",
                                "description", "子命令：" + commandsSummary(),
                                "enum", List.copyOf(COMMANDS.keySet()))
                ),
                "required", List.of(COMMAND)
        );
    }

    static String commandsSummary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : COMMANDS.entrySet()) {
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
        return switch (command) {
            case "start" -> WhaleHost.start();
            case "stop" -> WhaleHost.stop();
            case "status" -> WhaleHost.status();
            case "log" -> WhaleHost.log();
            default -> "未知命令：" + command + "。可选：" + commandsSummary();
        };
    }

    @Override
    public String uiTips() {
        return "工具链路径与 bili/bili_write 共用（填 dsh-bili-whale 的 tools 目录，宿主目录自动取其上级）。"
                + "填本条目输入框等价于填 bili 条目";
    }

    @Override
    public String uiInteractive(String input) {
        return BiliLocator.uiInteractive(input);
    }
}

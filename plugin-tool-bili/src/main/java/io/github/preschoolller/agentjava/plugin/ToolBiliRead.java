package io.github.preschoolller.agentjava.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * bili 只读工具：把 dsh-bili-whale 工具链（node bili.mjs <命令> [参数...]）里
 * 「对 B 站无写副作用」的命令暴露给模型。写命令在 bili_write 里，两者共用一份工具链路径。
 *
 * 只读名单以 bili.mjs 源里的分组为准（—— 读（不需要写操作，安全）—— 那一节），
 * 不收任何会发评论/私信/动态/弹幕/收藏的命令。
 */
public class ToolBiliRead implements Tool {

    static final String COMMAND = "command";
    static final String ARGS = "args";
    static final String TIMEOUT = "timeout_sec";

    /**
     * 只读命令白名单。key = bili.mjs 子命令，value = 一句话用途（拼进 command 的 description）。
     * 改名单时对照 dsh-bili-whale/tools/bili.mjs 的 map 表。
     */
    static final Map<String, String> READ_COMMANDS = Map.ofEntries(
            Map.entry("session", "确认当前凭据是哪个号（写操作前必跑）"),
            Map.entry("video", "只读文字还原视频内容：简介/字幕/弹幕/评论 <BV>"),
            Map.entry("subtitles", "拉官方字幕全文（带时间戳）：<BV> [--lan ai-zh]"),
            Map.entry("comments", "热评/最新评论（匿名可读）：<BV> [--ps 20] [--sort hot|time]"),
            Map.entry("feed", "选题器：按偏好捞候选视频 <BV> [--n 6]"),
            Map.entry("search", "按关键词搜视频"),
            Map.entry("whois", "按 mid 查昵称（@ 人前先核准）"),
            Map.entry("thread", "导出一条评论的完整楼中楼"),
            Map.entry("check-comment", "按 rpid 复核评论是否存在"),
            Map.entry("check-dynamic", "看动态的结构化内容"),
            Map.entry("check-mention", "确认动态里的 @ 是不是真提及"),
            Map.entry("watch", "盯一条评论的新回复（阻塞到命中或 --minutes 到点）"),
            Map.entry("comment-await", "阻塞等待「回复我的」，命中即退出（--minutes --interval）"),
            Map.entry("comment-digest", "今天谁找过她（回复+@ 收件箱汇总）--hours"),
            Map.entry("dm-await", "阻塞等待主人私信，命中打印 DM_HIT"),
            Map.entry("await", "阻塞等待直播间出现指定弹幕"),
            Map.entry("recon", "发评论前的侦查：BV→aid + 评论区状态"),
            Map.entry("covers", "抓视频元信息+封面图存本地 covers/"),
            Map.entry("likes", "谁赞了她（收到的赞通知流）--hours"),
            Map.entry("selftest", "启动自检：配置/凭据/登录态/目录/依赖"),
            Map.entry("deps", "依赖体检（cookie/ffmpeg/Node/目录）--json"),
            Map.entry("diary", "日记可见性：show / log / tail"),
            Map.entry("night", "生物钟：plan / clock / mark / status（只看计划用 status）"),
            Map.entry("traffic", "请求保护状态：node tools/bili.mjs traffic status"),
            Map.entry("quota", "限额闸：status / check --auto|--share / record / set / allow"),
            Map.entry("ai-share", "分享前评论抽样评估：sample <BV> / record <BV> <结果.json>"),
            Map.entry("debrief", "看懂一条视频（一条龙，含抽帧，较慢）：<BV> [--no-frames]"),
            Map.entry("watch-video", "真看视频：下载+ffmpeg 抽帧拼图（最慢，出图给看图能力）<BV> [3x3]")
    );

    @Override
    public String name() {
        return "bili";
    }

    @Override
    public String description() {
        return """
                B 站只读操作（bilibili.com，凭据为本机已配置的账号）。观 side-effect-free：
                看视频内容（video/subtitles/comments/debrief/watch-video）、选题（feed/search）、
                自检（selftest/deps/session）、收件箱（comment-digest/likes）、限额（quota status）、
                阻塞等待类（watch/comment-await/dm-await/await，注意 --minutes 别给太大）。
                发评论/私信/动态/弹幕/收藏等写操作一律走 bili_write 工具。""";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        COMMAND, Map.of(
                                "type", "string",
                                "description", "子命令，可选值及用途：" + commandsSummary(),
                                "enum", List.copyOf(READ_COMMANDS.keySet())),
                        ARGS, Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "参数列表（字符串数组，不要拼成一行）。具体用法以命令 --help 或文档为准"),
                        TIMEOUT, Map.of(
                                "type", "integer",
                                "description", "超时秒数，默认 120，上限 900。慢命令（debrief/watch-video）建议 300+")
                ),
                "required", List.of(COMMAND)
        );
    }

    static String commandsSummary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : READ_COMMANDS.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("；");
            }
            sb.append(e.getKey()).append("（").append(e.getValue()).append("）");
        }
        return sb.toString();
    }

    @Override
    public boolean readOnly() {
        return true;
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
        if (!READ_COMMANDS.containsKey(command)) {
            return "未知或非只读命令：" + command + "。只读命令见 schema enum；写操作请用 bili_write";
        }
        List<String> cmdArgs = new ArrayList<>();
        cmdArgs.add(command);
        JsonNode args = json.path(ARGS);
        if (args.isArray()) {
            ((ArrayNode) args).forEach(a -> cmdArgs.add(a.asText()));
        }
        return BiliRunner.run(cmdArgs, BiliRunner.clampTimeout(
                json.has(TIMEOUT) && json.path(TIMEOUT).isInt() ? json.path(TIMEOUT).asInt() : null));
    }

    @Override
    public String uiTips() {
        return "填 dsh-bili-whale 的 tools 目录（含 bili.mjs）或 bili.mjs 完整路径；留空则探测默认安装位置。"
                + "bili 与 bili_write 两个工具共用这一份工具链";
    }

    @Override
    public String uiInteractive(String input) {
        return BiliLocator.uiInteractive(input);
    }
}

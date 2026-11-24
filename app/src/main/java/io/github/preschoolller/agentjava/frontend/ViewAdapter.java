package io.github.preschoolller.agentjava.frontend;

import io.github.preschoolller.agentjava.core.enums.ToolState;
import io.github.preschoolller.agentjava.core.records.McpServerView;
import io.github.preschoolller.agentjava.core.records.MessageView;
import io.github.preschoolller.agentjava.core.records.ModelView;
import io.github.preschoolller.agentjava.core.records.ProfileView;
import io.github.preschoolller.agentjava.core.records.ToolView;
import io.github.preschoolller.agentjava.core.records.UsageView;
import io.github.preschoolller.agentjava.core.repository.McpServer;
import io.github.preschoolller.agentjava.core.repository.ModelMeta;
import io.github.preschoolller.agentjava.core.repository.Profile;

import java.util.ArrayList;
import java.util.List;

/**
 * 投影到渲染载体的适配：只做字段搬运，不持有状态、不写仓库。
 *
 * <p>{@link Message} 是卡片绘制用的纯数据 record（无持久化逻辑），
 * 从 {@link MessageView} 构造后只用于显示。
 */
public final class ViewAdapter {

    private ViewAdapter() {
    }

    public static Message message(MessageView view) {
        if (view == null) {
            return null;
        }
        String role = switch (view.role()) {
            case USER -> Message.ROLE_USER;
            case ASSISTANT -> Message.ROLE_AI;
            case TOOL_RESULT -> Message.ROLE_TOOL;
        };
        ToolView tool = view.tool();
        if (tool == null) {
            return new Message(role, view.text(), view.time(), null, null, null, null,
                    view.thinking(), view.messageId());
        }
        return new Message(role, view.text(), view.time(), tool.name(), tool.argsSummary(),
                toolStatus(tool.state()), tool.callId(), view.thinking(), view.messageId());
    }

    public static String toolStatus(ToolState state) {
        return switch (state) {
            case RUNNING -> "running";
            case OK -> "ok";
            case ERROR -> "error";
            case INTERRUPTED -> "interrupted";
            case BG_RUNNING -> "bg-running";
            case BG_DONE -> "bg-done";
            case BG_KILLED -> "bg-killed";
        };
    }

    public static String usageLabel(UsageView usage) {
        if (usage == null) {
            return "";
        }
        return String.format("↑%,d ↓%,d · %.1fs", usage.inputTokens(), usage.outputTokens(),
                usage.elapsedSeconds());
    }

    // ---------- 双枚举（core 投影 ↔ SPI 输入区） ----------

    public static io.github.preschoolller.agentjava.core.enums.ReasoningEffort coreEffort(
            io.github.preschoolller.agentjava.spi.enums.ReasoningEffortEnum effort) {
        return effort == null ? null
                : io.github.preschoolller.agentjava.core.enums.ReasoningEffort.valueOf(effort.name());
    }

    public static io.github.preschoolller.agentjava.spi.enums.ReasoningEffortEnum spiEffort(
            io.github.preschoolller.agentjava.core.enums.ReasoningEffort effort) {
        return effort == null ? null
                : io.github.preschoolller.agentjava.spi.enums.ReasoningEffortEnum.valueOf(effort.name());
    }

    public static io.github.preschoolller.agentjava.core.enums.AgentMode coreMode(
            io.github.preschoolller.agentjava.spi.enums.AgentMode mode) {
        return mode == null ? null
                : io.github.preschoolller.agentjava.core.enums.AgentMode.valueOf(mode.name());
    }

    public static io.github.preschoolller.agentjava.spi.enums.AgentMode spiMode(
            io.github.preschoolller.agentjava.core.enums.AgentMode mode) {
        return mode == null ? null
                : io.github.preschoolller.agentjava.spi.enums.AgentMode.valueOf(mode.name());
    }

    // ---------- 配置投影 ↔ 编辑用数据类 ----------

    /**
     * 方案投影 → 编辑用工作副本。{@code key} 为 null（投影不回传）时留空，
     * 保存时空白会被 {@link #profileView} 转回 null，语义是「不更改已落库的值」。
     */
    public static Profile profile(ProfileView view) {
        Profile profile = new Profile();
        profile.setApiType(view.apiType());
        profile.setBackendUrl(view.backendUrl());
        profile.setKey(view.key() == null ? "" : view.key());
        List<ModelMeta> metas = new ArrayList<>();
        for (ModelView model : view.models()) {
            metas.add(modelMeta(model));
        }
        profile.setModel(view.model());
        profile.setModelMetas(metas);
        profile.setTemperature(view.temperature());
        profile.setMaxTokens(view.maxTokens());
        profile.setSystemPrompt(view.systemPrompt());
        return profile;
    }

    /** 编辑用工作副本 → 方案投影；key 空白按「不更改」提交（null） */
    public static ProfileView profileView(String name, Profile profile) {
        List<ModelView> models = new ArrayList<>();
        for (ModelMeta meta : profile.getModelMetas()) {
            models.add(modelView(meta));
        }
        String key = profile.getKey();
        return new ProfileView(name, profile.getApiType(), profile.getBackendUrl(),
                key == null || key.isBlank() ? null : key, profile.getModel(), List.copyOf(models),
                profile.getTemperature(), profile.getMaxTokens(), profile.getSystemPrompt());
    }

    public static ModelMeta modelMeta(ModelView view) {
        ModelMeta meta = new ModelMeta(view.name());
        meta.setCtxWindow(view.ctxWindow());
        meta.setMaxOutput(view.maxOutput());
        meta.setReasoning(view.reasoning());
        meta.setText(view.text());
        meta.setImage(view.image());
        return meta;
    }

    public static ModelView modelView(ModelMeta meta) {
        return new ModelView(meta.getName(), meta.getCtxWindow(), meta.getMaxOutput(),
                meta.getReasoning(), meta.isText(), meta.isImage());
    }

    /** 跨方案找模型元数据（输入区下拉跨方案列模型，思考强度带出用）；同名取靠前的方案 */
    public static ModelView findModel(List<ProfileView> profiles, String modelName) {
        if (modelName == null) {
            return null;
        }
        for (ProfileView profile : profiles) {
            for (ModelView model : profile.models()) {
                if (modelName.equals(model.name())) {
                    return model;
                }
            }
        }
        return null;
    }

    /** MCP 配置投影 → 编辑用工作副本；授权类请求头/环境变量读出时缺失，保存时按「不更改」对待 */
    public static McpServer mcpServer(McpServerView view) {
        McpServer server = new McpServer();
        server.setName(view.name());
        server.setTransport(view.transport());
        server.setProtocol(view.protocol());
        server.setEnabled(view.enabled());
        server.setCommand(view.command());
        server.setArgs(view.args());
        server.setEnv(view.env());
        server.setUrl(view.url());
        server.setHeaders(view.headers());
        return server;
    }

    /** 编辑用工作副本 → 配置投影 */
    public static McpServerView mcpServerView(McpServer server) {
        return new McpServerView(server.getName(), server.getTransport(), server.getProtocol(),
                server.isEnabled(), server.getCommand(), server.getArgs(), server.getEnv(),
                server.getUrl(), server.getHeaders());
    }
}

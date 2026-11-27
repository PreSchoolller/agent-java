package io.github.preschoolller.agentjava.core.interfaces;

import io.github.preschoolller.agentjava.core.enums.AgentMode;
import io.github.preschoolller.agentjava.core.enums.ReasoningEffort;
import io.github.preschoolller.agentjava.core.records.McpServerView;
import io.github.preschoolller.agentjava.core.records.ProfileView;

import java.util.List;

/**
 * 运行时配置：模型方案、Provider 选择、上下文处理器、工具名单、MCP 服务器、插件输入。
 *
 * <p>写命令一律「提交即生效」：实现方负责落盘与通知既有监听者。没有单独的 save()，
 * 前端也不攒批提交——面板的「取消」靠丢弃工作副本完成，与这里无关。
 *
 * <p>界面偏好（主题、字号、窗口位置、日志）不在这个面里：那是宿主自己的事，
 * 见 {@code frontend.prefs.AppPreferences}。
 */
public interface RuntimeConfig {
    // —— 方案（profile）——

    /** 全部方案，顺序即展示顺序 */
    List<ProfileView> profiles();

    /** API 类型取值域（response / chatcompletion / messages），模型服务页按它分组 */
    List<String> apiTypes();

    /**
     * 整表替换方案集合：顺序保留，名字即键。空列表视为非法提交（至少保留一套）。
     * {@link ProfileView#key()} 为 null 表示沿用已落库的值。
     */
    void applyProfiles(List<ProfileView> profiles);

    // —— 全局单选：不随方案，按 API 类型各选一个 provider ——

    /** 该类型下选中的 provider 名；未指定返回 null（前端按注册表字典序预选） */
    String activeProviderName(String apiType);

    void setActiveProviderName(String apiType, String name);

    /** 上下文处理器名；空串 = 不启用 */
    String activeContextHandler();

    void setActiveContextHandler(String name);

    // —— 工具与枚举取值域 ——

    List<String> enabledTools();                 // null = 从未配置

    void setEnabledTools(List<String> names);    // null = 恢复默认

    List<ReasoningEffort> reasoningEfforts();    // 下拉框取值域

    List<AgentMode> agentModes();

    ReasoningEffort defaultEffort();             // 会话未盖戳时的回落值

    void setDefaultEffort(ReasoningEffort effort);

    AgentMode defaultMode();

    void setDefaultMode(AgentMode mode);

    String toolProperty(String toolId, String key);

    void setToolProperty(String toolId, String key, String value);

    // —— MCP ——

    List<McpServerView> mcpServers();

    /** 整表提交：名字唯一且非空由实现方校验，非法整次拒绝（别静默洗配置） */
    void setMcpServers(List<McpServerView> servers);

    // —— 插件 ——

    /** 插件交互工具的上次输入（键=插件名），后端 PluginBase.getPersistentInput 同名消费 */
    String pluginInput(String pluginName);

    void setPluginInput(String pluginName, String input);

    // —— 工作区 ——

    String defaultWorkspace();                   // 只读，无 setter

    /** 工作区路径规范化（相对路径转绝对、去尾斜杠）；新建会话弹窗与命令两侧用同一套规则 */
    String normalizeWorkspace(String raw);
}

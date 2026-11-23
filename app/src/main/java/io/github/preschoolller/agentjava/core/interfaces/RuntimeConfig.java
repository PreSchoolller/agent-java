package io.github.preschoolller.agentjava.core.interfaces;

import io.github.preschoolller.agentjava.core.enums.AgentMode;
import io.github.preschoolller.agentjava.core.enums.ReasoningEffort;
import io.github.preschoolller.agentjava.core.records.McpServerView;
import io.github.preschoolller.agentjava.core.records.ProfileView;

import java.util.List;

public interface RuntimeConfig {
    List<ProfileView> profiles();

    List<String> enabledTools();                 // null = 从未配置

    void setEnabledTools(List<String> names);    // null = 恢复默认

    List<ReasoningEffort> reasoningEfforts();    // 下拉框取值域

    List<AgentMode> agentModes();

    ReasoningEffort defaultEffort();             // 会话未盖戳时的回落值

    void setDefaultEffort(ReasoningEffort effort);

    AgentMode defaultMode();

    void setDefaultMode(AgentMode mode);

    List<McpServerView> mcpServers();

    String toolProperty(String toolId, String key);

    void setToolProperty(String toolId, String key, String value);

    String defaultWorkspace();                   // 只读，无 setter
}
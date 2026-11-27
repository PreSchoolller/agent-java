package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.core.enums.AgentMode;
import io.github.preschoolller.agentjava.core.enums.ReasoningEffort;
import io.github.preschoolller.agentjava.core.interfaces.RuntimeConfig;
import io.github.preschoolller.agentjava.core.records.McpServerView;
import io.github.preschoolller.agentjava.core.records.ProfileView;

import java.util.List;

public class RuntimeConfigImpl implements RuntimeConfig {
    @Override
    public List<ProfileView> profiles() {
        return List.of();
    }

    @Override
    public List<String> apiTypes() {
        return List.of();
    }

    @Override
    public void applyProfiles(List<ProfileView> profiles) {

    }

    @Override
    public String activeProviderName(String apiType) {
        return "";
    }

    @Override
    public void setActiveProviderName(String apiType, String name) {

    }

    @Override
    public String activeContextHandler() {
        return "";
    }

    @Override
    public void setActiveContextHandler(String name) {

    }

    @Override
    public List<String> enabledTools() {
        return List.of();
    }

    @Override
    public void setEnabledTools(List<String> names) {

    }

    @Override
    public List<ReasoningEffort> reasoningEfforts() {
        return List.of();
    }

    @Override
    public List<AgentMode> agentModes() {
        return List.of();
    }

    @Override
    public ReasoningEffort defaultEffort() {
        return null;
    }

    @Override
    public void setDefaultEffort(ReasoningEffort effort) {

    }

    @Override
    public AgentMode defaultMode() {
        return null;
    }

    @Override
    public void setDefaultMode(AgentMode mode) {

    }

    @Override
    public String toolProperty(String toolId, String key) {
        return "";
    }

    @Override
    public void setToolProperty(String toolId, String key, String value) {

    }

    @Override
    public List<McpServerView> mcpServers() {
        return List.of();
    }

    @Override
    public void setMcpServers(List<McpServerView> servers) {

    }

    @Override
    public String pluginInput(String pluginName) {
        return "";
    }

    @Override
    public void setPluginInput(String pluginName, String input) {

    }

    @Override
    public String defaultWorkspace() {
        return "";
    }

    @Override
    public String normalizeWorkspace(String raw) {
        return "";
    }
}

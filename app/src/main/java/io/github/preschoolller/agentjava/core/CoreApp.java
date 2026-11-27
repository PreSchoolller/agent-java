package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.core.enums.AgentMode;
import io.github.preschoolller.agentjava.core.enums.ReasoningEffort;
import io.github.preschoolller.agentjava.core.interfaces.AgentApp;
import io.github.preschoolller.agentjava.core.interfaces.AppCommands;
import io.github.preschoolller.agentjava.core.interfaces.AppEvents;
import io.github.preschoolller.agentjava.core.interfaces.AppView;
import io.github.preschoolller.agentjava.core.interfaces.EventListener;
import io.github.preschoolller.agentjava.core.interfaces.HostRequests;
import io.github.preschoolller.agentjava.core.interfaces.RuntimeConfig;
import io.github.preschoolller.agentjava.core.records.McpServerView;
import io.github.preschoolller.agentjava.core.records.ProfileView;
import io.github.preschoolller.agentjava.core.records.SessionSummary;
import io.github.preschoolller.agentjava.core.records.SessionView;
import io.github.preschoolller.agentjava.core.records.Snapshot;
import io.github.preschoolller.agentjava.core.repository.ConversationRepository;

import java.util.List;

public class CoreApp implements AgentApp {

    private final AppView appView;
    private final AppCommands appCommands;
    private final AppEvents appEvents;
    private final HostRequests hostRequests;
    private final RuntimeConfig runtimeConfig;
    private final AgentLoop agentLoop;
    private final ConversationRepository conversationRepository;

    public CoreApp() {
        this.conversationRepository = new ConversationRepository();
        this.appCommands = new AppCommandsImpl();
        AppEventsImpl appEventsImpl = new AppEventsImpl();
        this.appEvents = appEventsImpl;
        HostRequestsImpl hostRequestsImpl = new HostRequestsImpl(appEventsImpl);
        this.hostRequests = hostRequestsImpl;
        this.runtimeConfig = new RuntimeConfigImpl();
        this.agentLoop = new AgentLoop(conversationRepository, appEventsImpl, hostRequestsImpl);
        this.appView = new AppViewImpl(conversationRepository, appEventsImpl, agentLoop);
    }

    // ----- AppCommands -----

    @Override
    public CreateSessionResult createSession(String workspace, String reuseIfEmptySessionId) {
        return appCommands.createSession(workspace, reuseIfEmptySessionId);
    }

    @Override
    public String ensureSession(String sessionId, String workspace) {
        return appCommands.ensureSession(sessionId, workspace);
    }

    @Override
    public void renameSession(String sessionId, String title) {
        appCommands.renameSession(sessionId, title);
    }

    @Override
    public void pinSession(String sessionId, boolean pinned) {
        appCommands.pinSession(sessionId, pinned);
    }

    @Override
    public void deleteSession(String sessionId) {
        appCommands.deleteSession(sessionId);
    }

    @Override
    public void deleteSessionIfEmpty(String sessionId) {
        appCommands.deleteSessionIfEmpty(sessionId);
    }

    @Override
    public String forkSession(String sessionId, String fromMessageId) {
        return appCommands.forkSession(sessionId, fromMessageId);
    }

    @Override
    public void submit(String sessionId, String clientMsgId, String text) {
        appCommands.submit(sessionId, clientMsgId, text);
    }

    @Override
    public void cancel(String sessionId) {
        appCommands.cancel(sessionId);
    }

    @Override
    public void regenerate(String sessionId, String fromMessageId) {
        appCommands.regenerate(sessionId, fromMessageId);
    }

    @Override
    public void setWorkspace(String sessionId, String workspace) {
        appCommands.setWorkspace(sessionId, workspace);
    }

    @Override
    public void setModel(String sessionId, String profileName, String modelName) {
        appCommands.setModel(sessionId, profileName, modelName);
    }

    @Override
    public void setReasoningEffort(String sessionId, ReasoningEffort effort) {
        appCommands.setReasoningEffort(sessionId, effort);
    }

    @Override
    public void setAgentMode(String sessionId, AgentMode mode) {
        appCommands.setAgentMode(sessionId, mode);
    }

    // ----- HostRequests -----

    @Override
    public List<Request> pending() {
        return hostRequests.pending();
    }

    @Override
    public void answer(String requestId, Answer answer) {
        hostRequests.answer(requestId, answer);
    }

    @Override
    public void cancelRequest(String requestId) {
        hostRequests.cancelRequest(requestId);
    }

    // ----- AppEvents -----

    @Override
    public AutoCloseable subscribe(long sinceSeq, EventListener listener) {
        return appEvents.subscribe(sinceSeq, listener);
    }

    // ----- AppView -----

    @Override
    public Snapshot snapshot() {
        return appView.snapshot();
    }

    @Override
    public SessionView session(String sessionId) {
        return appView.session(sessionId);
    }

    // ----- RuntimeConfig -----

    @Override
    public List<ProfileView> profiles() {
        return runtimeConfig.profiles();
    }

    @Override
    public List<String> apiTypes() {
        return runtimeConfig.apiTypes();
    }

    @Override
    public void applyProfiles(List<ProfileView> profiles) {
        runtimeConfig.applyProfiles(profiles);
    }

    @Override
    public String activeProviderName(String apiType) {
        return runtimeConfig.activeProviderName(apiType);
    }

    @Override
    public void setActiveProviderName(String apiType, String name) {
        runtimeConfig.setActiveProviderName(apiType, name);
    }

    @Override
    public String activeContextHandler() {
        return runtimeConfig.activeContextHandler();
    }

    @Override
    public void setActiveContextHandler(String name) {
        runtimeConfig.setActiveContextHandler(name);
    }

    @Override
    public List<String> enabledTools() {
        return runtimeConfig.enabledTools();
    }

    @Override
    public void setEnabledTools(List<String> names) {
        runtimeConfig.setEnabledTools(names);
    }

    @Override
    public List<ReasoningEffort> reasoningEfforts() {
        return runtimeConfig.reasoningEfforts();
    }

    @Override
    public List<AgentMode> agentModes() {
        return runtimeConfig.agentModes();
    }

    @Override
    public ReasoningEffort defaultEffort() {
        return runtimeConfig.defaultEffort();
    }

    @Override
    public void setDefaultEffort(ReasoningEffort effort) {
        runtimeConfig.setDefaultEffort(effort);
    }

    @Override
    public AgentMode defaultMode() {
        return runtimeConfig.defaultMode();
    }

    @Override
    public void setDefaultMode(AgentMode mode) {
        runtimeConfig.setDefaultMode(mode);
    }

    @Override
    public String toolProperty(String toolId, String key) {
        return runtimeConfig.toolProperty(toolId, key);
    }

    @Override
    public void setToolProperty(String toolId, String key, String value) {
        runtimeConfig.setToolProperty(toolId, key, value);
    }

    @Override
    public List<McpServerView> mcpServers() {
        return runtimeConfig.mcpServers();
    }

    @Override
    public void setMcpServers(List<McpServerView> servers) {
        runtimeConfig.setMcpServers(servers);
    }

    @Override
    public String pluginInput(String pluginName) {
        return runtimeConfig.pluginInput(pluginName);
    }

    @Override
    public void setPluginInput(String pluginName, String input) {
        runtimeConfig.setPluginInput(pluginName, input);
    }

    @Override
    public String defaultWorkspace() {
        return runtimeConfig.defaultWorkspace();
    }

    @Override
    public String normalizeWorkspace(String raw) {
        return runtimeConfig.normalizeWorkspace(raw);
    }
}

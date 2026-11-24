package io.github.preschoolller.agentjava.frontend.gui;

import io.github.preschoolller.agentjava.core.enums.AgentMode;
import io.github.preschoolller.agentjava.core.enums.MessageState;
import io.github.preschoolller.agentjava.core.enums.ReasoningEffort;
import io.github.preschoolller.agentjava.core.enums.Role;
import io.github.preschoolller.agentjava.core.enums.SessionStatus;
import io.github.preschoolller.agentjava.core.enums.ToolState;
import io.github.preschoolller.agentjava.core.events.StateEvent;
import io.github.preschoolller.agentjava.core.interfaces.AgentApp;
import io.github.preschoolller.agentjava.core.interfaces.EventListener;
import io.github.preschoolller.agentjava.core.interfaces.HostRequests;
import io.github.preschoolller.agentjava.core.records.McpServerView;
import io.github.preschoolller.agentjava.core.records.MessageView;
import io.github.preschoolller.agentjava.core.records.ProfileView;
import io.github.preschoolller.agentjava.core.records.SessionSummary;
import io.github.preschoolller.agentjava.core.records.SessionView;
import io.github.preschoolller.agentjava.core.records.Snapshot;
import io.github.preschoolller.agentjava.core.records.ToolView;
import io.github.preschoolller.agentjava.core.records.UsageView;
import io.github.preschoolller.agentjava.core.repository.AppSettings;
import io.github.preschoolller.agentjava.core.repository.Conversation;
import io.github.preschoolller.agentjava.core.repository.Message;
import io.github.preschoolller.agentjava.core.repository.McpServer;
import io.github.preschoolller.agentjava.core.repository.Profile;
import io.github.preschoolller.agentjava.frontend.ViewAdapter;
import io.github.preschoolller.agentjava.frontend.store.ConversationStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * GUI 测试用的 AgentApp 桩：数据由 {@link ConversationStore} 造，读出口一律转成投影。
 * 只实现测试需要的行为，命令直接作用于 store（等价于一个最小的内存内核）。
 */
public final class TestApp implements AgentApp {

    private final ConversationStore store;
    private long seq;

    public TestApp(ConversationStore store) {
        this.store = store;
    }

    public ConversationStore store() {
        return store;
    }

    // ---------- AppView ----------

    @Override
    public Snapshot snapshot() {
        List<SessionSummary> sessions = new ArrayList<>();
        for (Conversation conversation : store.getConversations()) {
            sessions.add(summary(conversation));
        }
        return new Snapshot(seq, List.copyOf(sessions));
    }

    @Override
    public SessionView session(String sessionId) {
        Conversation conversation = store.getConversation(sessionId);
        return conversation == null ? null : view(conversation);
    }

    @Override
    public List<SessionSummary> sessionsSince(long sinceSeq) {
        return List.of();
    }

    // ---------- AppCommands ----------

    @Override
    public CreateSessionResult createSession(String workspace, String reuseIfEmptySessionId) {
        if (reuseIfEmptySessionId != null) {
            Conversation existing = store.getConversation(reuseIfEmptySessionId);
            if (existing != null && existing.isKnownEmpty()) {
                existing.setWorkspace(workspace);
                store.switchTo(existing.getId());
                return new CreateSessionResult(existing.getId(), true);
            }
        }
        Conversation created = store.newConversation(workspace);
        store.switchTo(created.getId());
        return new CreateSessionResult(created.getId(), false);
    }

    @Override
    public String ensureSession(String sessionId, String workspace) {
        return store.getOrCreate(sessionId, workspace).getId();
    }

    @Override
    public void renameSession(String sessionId, String title) {
        store.rename(sessionId, title);
    }

    @Override
    public void pinSession(String sessionId, boolean pinned) {
        store.setPinned(sessionId, pinned);
    }

    @Override
    public void deleteSession(String sessionId) {
        store.remove(sessionId);
    }

    @Override
    public void deleteSessionIfEmpty(String sessionId) {
        Conversation conversation = store.getConversation(sessionId);
        if (conversation != null && conversation.isKnownEmpty()) {
            store.remove(sessionId);
        }
    }

    @Override
    public String forkSession(String sessionId, String fromMessageId) {
        Conversation forked = store.fork(sessionId, fromMessageId);
        return forked == null ? null : forked.getId();
    }

    @Override
    public void submit(String sessionId, String clientMsgId, String text) {
        // 测试只验 UI：由用例直接调 addUserMessage/openAiCard，这里不模拟整条链
    }

    @Override
    public void regenerate(String sessionId, String fromMessageId) {
    }

    /** 会话取消与审批撤回在 AgentApp 上签名相同（HostRequests.cancel 与 AppCommands.cancel），
     *  一个实现同时满足两者；测试里无副作用。 */
    @Override
    public void cancel(String id) {
    }

    @Override
    public void setWorkspace(String sessionId, String workspace) {
        Conversation conversation = store.getConversation(sessionId);
        if (conversation != null) {
            conversation.setWorkspace(workspace);
        }
    }

    @Override
    public void setModel(String sessionId, String profileName, String modelName) {
        Conversation conversation = store.getConversation(sessionId);
        if (conversation != null) {
            conversation.setModelOption(profileName, modelName);
        }
    }

    @Override
    public void setReasoningEffort(String sessionId, ReasoningEffort effort) {
        Conversation conversation = store.getConversation(sessionId);
        if (conversation != null) {
            conversation.setDialogPreferences(ViewAdapter.spiEffort(effort), conversation.getAgentMode());
        }
    }

    @Override
    public void setAgentMode(String sessionId, AgentMode mode) {
        Conversation conversation = store.getConversation(sessionId);
        if (conversation != null) {
            conversation.setDialogPreferences(conversation.getReasoningEffort(), ViewAdapter.spiMode(mode));
        }
    }

    // ---------- AppEvents ----------

    @Override
    public AutoCloseable subscribe(long sinceSeq, EventListener listener) {
        return () -> {
        };
    }

    // ---------- HostRequests ----------

    @Override
    public List<Request> pending() {
        return List.of();
    }

    @Override
    public void answer(String requestId, Answer answer) {
    }

    // ---------- RuntimeConfig ----------

    /** 配置面直接代理到 AppSettings：测试用它造数据、再用投影读回，等价于一个最小内核 */
    @Override
    public List<ProfileView> profiles() {
        List<ProfileView> views = new ArrayList<>();
        for (String name : AppSettings.get().getProfileNames()) {
            views.add(ViewAdapter.profileView(name, AppSettings.get().getProfile(name)));
        }
        return List.copyOf(views);
    }

    @Override
    public List<String> apiTypes() {
        return Profile.API_TYPES;
    }

    @Override
    public void applyProfiles(List<ProfileView> profiles) {
        LinkedHashMap<String, Profile> replacement = new LinkedHashMap<>();
        for (ProfileView view : profiles) {
            replacement.put(view.name(), ViewAdapter.profile(view));
        }
        AppSettings.get().applyProfiles(replacement);
    }

    @Override
    public String activeProviderName(String apiType) {
        return AppSettings.get().getActiveProviderName(apiType);
    }

    @Override
    public void setActiveProviderName(String apiType, String name) {
        AppSettings.get().setActiveProviderName(apiType, name);
    }

    @Override
    public String activeContextHandler() {
        return AppSettings.get().getActiveContextHandler();
    }

    @Override
    public void setActiveContextHandler(String name) {
        AppSettings.get().setActiveContextHandler(name);
    }

    @Override
    public List<String> enabledTools() {
        return AppSettings.get().getEnabledTools();
    }

    @Override
    public void setEnabledTools(List<String> names) {
        AppSettings.get().setEnabledTools(names);
    }

    @Override
    public List<ReasoningEffort> reasoningEfforts() {
        return List.of(ReasoningEffort.values());
    }

    @Override
    public List<AgentMode> agentModes() {
        return List.of(AgentMode.values());
    }

    @Override
    public ReasoningEffort defaultEffort() {
        return ReasoningEffort.MEDIUM;
    }

    @Override
    public void setDefaultEffort(ReasoningEffort effort) {
    }

    @Override
    public AgentMode defaultMode() {
        return AgentMode.EDIT;
    }

    @Override
    public void setDefaultMode(AgentMode mode) {
    }

    @Override
    public List<McpServerView> mcpServers() {
        List<McpServerView> views = new ArrayList<>();
        for (McpServer server : AppSettings.get().getMcpServers()) {
            views.add(ViewAdapter.mcpServerView(server));
        }
        return List.copyOf(views);
    }

    @Override
    public void setMcpServers(List<McpServerView> servers) {
        List<McpServer> list = new ArrayList<>();
        for (McpServerView view : servers) {
            list.add(ViewAdapter.mcpServer(view));
        }
        AppSettings.get().setMcpServers(list);
    }

    @Override
    public String pluginInput(String pluginName) {
        return AppSettings.get().getPersistentPluginInput(pluginName);
    }

    @Override
    public void setPluginInput(String pluginName, String input) {
        AppSettings.get().setPersistentPluginInput(pluginName, input);
    }

    @Override
    public String toolProperty(String toolId, String key) {
        return null;
    }

    @Override
    public void setToolProperty(String toolId, String key, String value) {
    }

    @Override
    public String defaultWorkspace() {
        return AppSettings.defaultWorkspace().toString();
    }

    @Override
    public String normalizeWorkspace(String raw) {
        return AppSettings.normalizeWorkspace(raw);
    }

    // ---------- 投影转换 ----------

    private SessionSummary summary(Conversation conversation) {
        return new SessionSummary(conversation.getId(), conversation.getTitle(), conversation.isPinned(),
                conversation.getWorkspace(), SessionStatus.IDLE, conversation.getUpdatedAt());
    }

    private SessionView view(Conversation conversation) {
        List<MessageView> messages = new ArrayList<>();
        for (Message message : conversation.getMessages()) {
            Role role = Conversation.ROLE_USER.equals(message.role()) ? Role.USER
                    : Conversation.ROLE_TOOL.equals(message.role()) ? Role.TOOL_RESULT : Role.ASSISTANT;
            ToolView tool = message.isToolCall()
                    ? new ToolView(message.callId(), message.toolName(), message.toolArgs(),
                    toolState(message.toolStatus()), false)
                    : null;
            messages.add(new MessageView(message.messageId(), null, role, message.text(),
                    message.thinking(), message.time(), MessageState.CONFIRMED, tool));
        }
        Conversation.Usage usage = conversation.getUsage();
        return new SessionView(conversation.getId(), conversation.getTitle(), conversation.getWorkspace(),
                SessionStatus.IDLE, conversation.getModelName(), conversation.getModelProfile(),
                ViewAdapter.coreEffort(conversation.getReasoningEffort()),
                ViewAdapter.coreMode(conversation.getAgentMode()),
                usage == null ? null : new UsageView(usage.inputTokens(), usage.outputTokens(),
                        usage.elapsedSeconds()),
                List.copyOf(messages));
    }

    private static ToolState toolState(String status) {
        if (status == null) {
            return ToolState.RUNNING;
        }
        return switch (status) {
            case "ok" -> ToolState.OK;
            case "error" -> ToolState.ERROR;
            case "interrupted" -> ToolState.INTERRUPTED;
            case "bg-running" -> ToolState.BG_RUNNING;
            case "bg-done" -> ToolState.BG_DONE;
            case "bg-killed" -> ToolState.BG_KILLED;
            default -> ToolState.RUNNING;
        };
    }

    /** 事件构造辅助：用例需要发事件时用 */
    public static StateEvent event(long seq, StateEvent body) {
        return body;
    }
}

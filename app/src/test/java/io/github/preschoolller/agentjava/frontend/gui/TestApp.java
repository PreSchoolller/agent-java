package io.github.preschoolller.agentjava.frontend.gui;

import io.github.preschoolller.agentjava.core.enums.AgentMode;
import io.github.preschoolller.agentjava.core.enums.MessageState;
import io.github.preschoolller.agentjava.core.enums.ReasoningEffort;
import io.github.preschoolller.agentjava.core.enums.Role;
import io.github.preschoolller.agentjava.core.enums.SessionStatus;
import io.github.preschoolller.agentjava.core.enums.ToolState;
import io.github.preschoolller.agentjava.core.interfaces.AgentApp;
import io.github.preschoolller.agentjava.core.interfaces.EventListener;
import io.github.preschoolller.agentjava.core.records.McpServerView;
import io.github.preschoolller.agentjava.core.records.MessageView;
import io.github.preschoolller.agentjava.core.records.ProfileView;
import io.github.preschoolller.agentjava.core.records.SessionSummary;
import io.github.preschoolller.agentjava.core.records.SessionView;
import io.github.preschoolller.agentjava.core.records.Snapshot;
import io.github.preschoolller.agentjava.core.records.ToolView;
import io.github.preschoolller.agentjava.core.records.UsageView;
import io.github.preschoolller.agentjava.core.repository.AppSettings;
import io.github.preschoolller.agentjava.core.repository.McpServer;
import io.github.preschoolller.agentjava.core.repository.Profile;
import io.github.preschoolller.agentjava.frontend.adapter.Message;
import io.github.preschoolller.agentjava.frontend.adapter.ViewAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * GUI 测试用的 AgentApp 桩：自带一套内存会话模型，读出口一律转成投影。
 *
 * <p>夹具入口沿用 {@code newConversation / appendMessage / getConversations / getActive} 这套名字：
 * 用例造完数据后由 {@link Window} 通过 {@code AgentApp} 读回。生产代码不依赖本类。
 */
public final class TestApp implements AgentApp {

    /** 测试会话：消息、标题、工作区、模型与输入区偏好都放这里 */
    public static final class Session {

        private final String id = UUID.randomUUID().toString();
        private final List<Message> messages = new ArrayList<>();
        private String title;
        private String workspace;
        private boolean pinned;
        private long updatedAt = System.currentTimeMillis();
        private String modelName;
        private String modelProfile;
        private ReasoningEffort effort;
        private AgentMode mode;
        private UsageView usage;

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getWorkspace() {
            return workspace;
        }

        public void setWorkspace(String workspace) {
            this.workspace = workspace;
        }

        public boolean isPinned() {
            return pinned;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }

        public List<Message> getMessages() {
            return List.copyOf(messages);
        }

        public UsageView getUsage() {
            return usage;
        }

        public void setUsage(UsageView usage) {
            this.usage = usage;
        }
    }

    private final List<Session> sessions = new ArrayList<>();
    private String activeId;
    private long seq;

    // ---------- 夹具入口 ----------

    public Session newConversation() {
        return newConversation(null);
    }

    public Session newConversation(String workspace) {
        Session session = new Session();
        session.setWorkspace(workspace);
        sessions.add(session);
        activeId = session.getId();
        return session;
    }

    public List<Session> getConversations() {
        return List.copyOf(sessions);
    }

    /** 当前会话；没有会话时建一段空的（侧栏"至少有一个会话"的展示语义） */
    public Session getActive() {
        Session active = getConversation(activeId);
        if (active != null) {
            return active;
        }
        if (sessions.isEmpty()) {
            return newConversation();
        }
        active = sessions.get(0);
        activeId = active.getId();
        return active;
    }

    public Session getConversation(String sessionId) {
        for (Session session : sessions) {
            if (session.getId().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    public Message appendMessage(String sessionId, String role, String text) {
        Session session = require(sessionId);
        Message message = new Message(role, text, System.currentTimeMillis());
        session.messages.add(message);
        session.updatedAt = message.time();
        return message;
    }

    /** 追加一条工具消息（工具卡断言用） */
    public Message appendToolCall(String sessionId, String toolName, String args, String callId) {
        Session session = require(sessionId);
        Message message = new Message(Message.ROLE_TOOL, "", System.currentTimeMillis(),
                toolName, args, Message.STATUS_RUNNING, callId);
        session.messages.add(message);
        session.updatedAt = message.time();
        return message;
    }

    public void setPinned(String sessionId, boolean pinned) {
        Session session = getConversation(sessionId);
        if (session != null) {
            session.pinned = pinned;
        }
    }

    public void switchTo(String sessionId) {
        if (getConversation(sessionId) != null) {
            activeId = sessionId;
        }
    }

    private Session require(String sessionId) {
        Session session = getConversation(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return session;
    }

    // ---------- AppView ----------

    @Override
    public Snapshot snapshot() {
        List<SessionSummary> summaries = new ArrayList<>();
        for (Session session : sessions) {
            summaries.add(new SessionSummary(session.getId(), session.getTitle(), session.isPinned(),
                    session.getWorkspace(), SessionStatus.IDLE, session.getUpdatedAt()));
        }
        return new Snapshot(seq, List.copyOf(summaries));
    }

    @Override
    public SessionView session(String sessionId) {
        Session session = getConversation(sessionId);
        return session == null ? null : view(session);
    }

    @Override
    public List<SessionSummary> sessionsSince(long sinceSeq) {
        return List.of();
    }

    // ---------- AppCommands ----------

    @Override
    public CreateSessionResult createSession(String workspace, String reuseIfEmptySessionId) {
        if (reuseIfEmptySessionId != null) {
            Session existing = getConversation(reuseIfEmptySessionId);
            if (existing != null && existing.getMessages().isEmpty()) {
                existing.setWorkspace(workspace);
                activeId = existing.getId();
                return new CreateSessionResult(existing.getId(), true);
            }
        }
        return new CreateSessionResult(newConversation(workspace).getId(), false);
    }

    @Override
    public String ensureSession(String sessionId, String workspace) {
        Session session = getConversation(sessionId);
        if (session == null) {
            session = new Session();
            session.setWorkspace(workspace);
            sessions.add(session);
        }
        return session.getId();
    }

    @Override
    public void renameSession(String sessionId, String title) {
        Session session = getConversation(sessionId);
        if (session != null) {
            session.setTitle(title);
        }
    }

    @Override
    public void pinSession(String sessionId, boolean pinned) {
        setPinned(sessionId, pinned);
    }

    @Override
    public void deleteSession(String sessionId) {
        sessions.removeIf(session -> session.getId().equals(sessionId));
        if (sessionId != null && sessionId.equals(activeId)) {
            activeId = sessions.isEmpty() ? null : sessions.get(0).getId();
        }
    }

    @Override
    public void deleteSessionIfEmpty(String sessionId) {
        Session session = getConversation(sessionId);
        if (session != null && session.getMessages().isEmpty()) {
            deleteSession(sessionId);
        }
    }

    @Override
    public String forkSession(String sessionId, String fromMessageId) {
        Session source = getConversation(sessionId);
        if (source == null) {
            return null;
        }
        Session forked = newConversation(source.getWorkspace());
        for (Message message : source.getMessages()) {
            forked.messages.add(message);
            if (message.messageId().equals(fromMessageId)) {
                return forked.getId();
            }
        }
        return null;
    }

    @Override
    public void submit(String sessionId, String clientMsgId, String text) {
        // 测试只验 UI：用例直接调 addAiMessage/openAiCard，这里不模拟整条链
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
        Session session = getConversation(sessionId);
        if (session != null) {
            session.setWorkspace(workspace);
        }
    }

    @Override
    public void setModel(String sessionId, String profileName, String modelName) {
        Session session = getConversation(sessionId);
        if (session != null) {
            session.modelProfile = profileName;
            session.modelName = modelName;
        }
    }

    @Override
    public void setReasoningEffort(String sessionId, ReasoningEffort effort) {
        Session session = getConversation(sessionId);
        if (session != null) {
            session.effort = effort;
        }
    }

    @Override
    public void setAgentMode(String sessionId, AgentMode mode) {
        Session session = getConversation(sessionId);
        if (session != null) {
            session.mode = mode;
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

    // ---------- RuntimeConfig：代理到 AppSettings，等价于一个最小内核 ----------

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
    public String toolProperty(String toolId, String key) {
        return null;
    }

    @Override
    public void setToolProperty(String toolId, String key, String value) {
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
    public String defaultWorkspace() {
        return AppSettings.defaultWorkspace().toString();
    }

    @Override
    public String normalizeWorkspace(String raw) {
        return AppSettings.normalizeWorkspace(raw);
    }

    // ---------- 投影转换 ----------

    private SessionView view(Session session) {
        List<MessageView> messages = new ArrayList<>();
        for (Message message : session.getMessages()) {
            Role role = Message.ROLE_USER.equals(message.role()) ? Role.USER
                    : Message.ROLE_TOOL.equals(message.role()) ? Role.TOOL_RESULT : Role.ASSISTANT;
            ToolView tool = message.isToolCall()
                    ? new ToolView(message.callId(), message.toolName(), message.toolArgs(),
                    toolState(message.toolStatus()), false)
                    : null;
            messages.add(new MessageView(message.messageId(), null, role, message.text(),
                    message.thinking(), message.time(), MessageState.CONFIRMED, tool));
        }
        return new SessionView(session.getId(), session.getTitle(), session.getWorkspace(),
                SessionStatus.IDLE, session.modelName, session.modelProfile,
                session.effort, session.mode, session.getUsage(), List.copyOf(messages));
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
}

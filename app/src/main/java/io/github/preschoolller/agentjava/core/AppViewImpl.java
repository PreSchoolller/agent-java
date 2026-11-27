package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.core.converter.RecordConverter;
import io.github.preschoolller.agentjava.core.enums.AgentMode;
import io.github.preschoolller.agentjava.core.enums.ReasoningEffort;
import io.github.preschoolller.agentjava.core.enums.SessionStatus;
import io.github.preschoolller.agentjava.core.interfaces.AppView;
import io.github.preschoolller.agentjava.core.records.MessageView;
import io.github.preschoolller.agentjava.core.records.SessionSummary;
import io.github.preschoolller.agentjava.core.records.SessionView;
import io.github.preschoolller.agentjava.core.records.Snapshot;
import io.github.preschoolller.agentjava.core.records.UsageView;
import io.github.preschoolller.agentjava.core.repository.Conversation;
import io.github.preschoolller.agentjava.core.repository.ConversationRepository;
import io.github.preschoolller.agentjava.core.repository.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class AppViewImpl implements AppView {

    private final ConversationRepository repo;
    private final EventPublisher eventPublisher;
    private final AgentLoop agentLoop;
    private static final Logger LOG = LoggerFactory.getLogger(AppViewImpl.class);

    public AppViewImpl(ConversationRepository repo, EventPublisher eventPublisher, AgentLoop agentLoop) {
        this.repo = repo;
        this.eventPublisher = eventPublisher;
        this.agentLoop = agentLoop;
    }

    @Override
    public Snapshot snapshot() {
        CompletableFuture<List<Conversation>> conversationsFuture = new CompletableFuture<>();
        CompletableFuture<Map<String, SessionStatus>> sessionIdStatusMapFuture = new CompletableFuture<>();
        long seq = -1;
        try {
            seq = eventPublisher.read(() -> {
                List<Conversation> conversations = repo.getConversations();
                conversationsFuture.complete(conversations);
                sessionIdStatusMapFuture.complete(agentLoop.getSessionStatus(conversations));
            });
        } catch (Throwable e) {
            LOG.warn("throwable detected while getting snapshot", e);
        }
        if (seq == -1) {
            return null;
        }
        List<Conversation> conversations = List.of();
        Map<String, SessionStatus> stringSessionStatusMap = Map.of();
        try {
            conversations = conversationsFuture.get();
            stringSessionStatusMap = sessionIdStatusMapFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while reading session status", e);
        } catch (ExecutionException e) {
            LOG.warn("Error while reading session status", e);
        }
        List<SessionSummary> sessions = buildSessions(conversations, stringSessionStatusMap);
        return new Snapshot(seq, sessions);
    }

    private List<SessionSummary> buildSessions(List<Conversation> conversations,
                                               Map<String, SessionStatus> sessionIdStatusMap) {
        List<SessionSummary> sessionSummaries = new ArrayList<>();
        if (conversations == null) {
            return sessionSummaries;
        }
        for (Conversation conversation : conversations) {
            SessionStatus status = sessionIdStatusMap == null ? SessionStatus.IDLE : sessionIdStatusMap.get(conversation.getId());
            sessionSummaries.add(new SessionSummary(conversation.getId(), conversation.getTitle(),
                    conversation.isPinned(), conversation.getWorkspace(), status, conversation.getUpdatedAt()));
        }
        return sessionSummaries;
    }

    @Override
    public SessionView session(String sessionId) {
        CompletableFuture<Conversation> conversationFuture = new CompletableFuture<>();
        CompletableFuture<Map<String, SessionStatus>> sessionIdStatusMapFuture = new CompletableFuture<>();
        long seq = -1;
        try {
            seq = eventPublisher.read(() -> {
                Conversation conversation = repo.getConversation(sessionId);
                conversation.getMessages();
                conversationFuture.complete(conversation);
                sessionIdStatusMapFuture.complete(agentLoop.getSessionStatus(List.of(conversation)));
            });
        } catch (Throwable e) {
            LOG.warn("throwable detected while getting session", e);
        }
        Conversation conversation = null;
        Map<String, SessionStatus> sessionIdStatusMap = Map.of();
        try {
            conversation = conversationFuture.get();
            sessionIdStatusMap = sessionIdStatusMapFuture.get();
        } catch (ExecutionException e) {
            LOG.warn("Error while reading session status", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while reading session status", e);
        }
        if (conversation == null) {
            return null;
        }
        return buildSessionView(conversation, sessionIdStatusMap, seq);
    }

    private SessionView buildSessionView(Conversation conversation, Map<String, SessionStatus> sessionIdStatusMap, long seq) {
        SessionStatus status = sessionIdStatusMap == null
                ? SessionStatus.IDLE
                : sessionIdStatusMap.get(conversation.getId());
        status = status == null ? SessionStatus.IDLE : status;
        UsageView usageView = RecordConverter.toUsageView(conversation.getUsage());
        List<MessageView> messageViews = buildMessageViews(conversation.getMessages());
        String id = conversation.getId();
        String title = conversation.getTitle();
        String workspace =  conversation.getWorkspace();
        String modelName = conversation.getModelName();
        String modelProfile  = conversation.getModelProfile();
        ReasoningEffort effort = RecordConverter.toReasoningEffort(conversation.getReasoningEffort());
        AgentMode mode = RecordConverter.toAgentMode(conversation.getAgentMode());
        return new SessionView(id, title, workspace, status, modelName, modelProfile, effort, mode, usageView, messageViews, seq);
    }

    private List<MessageView> buildMessageViews(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ArrayList<>();
        }
        return messages.stream().map(RecordConverter::toMessageView).toList();
    }
}

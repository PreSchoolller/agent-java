package io.github.preschoolller.agentjava;

import io.github.preschoolller.agentjava.backend.maintenance.ShutDownJob;
import io.github.preschoolller.agentjava.backend.maintenance.TaskHandle;
import io.github.preschoolller.agentjava.core.events.NoticeLevel;
import io.github.preschoolller.agentjava.core.interfaces.HostRequests;
import io.github.preschoolller.agentjava.core.records.UsageView;
import io.github.preschoolller.agentjava.core.repository.AppSettings;
import io.github.preschoolller.agentjava.core.repository.Conversation;
import io.github.preschoolller.agentjava.frontend.gui.Window;
import io.github.preschoolller.agentjava.frontend.tmp.AgentSink;
import io.github.preschoolller.agentjava.frontend.tmp.AiWriter;
import io.github.preschoolller.agentjava.frontend.tmp.ToolWriter;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class SwingImp implements AgentSink {

    /**
     * 会话级 agent 循环锁（id → 本轮链的取消令牌）：不同会话各自并行，同一会话同一时刻最多一轮。
     * UI 的生成态置灰是第一道，这里是后端兜底；令牌随工具续轮传递，整条链结束才移除条目。
     * 各会话各拿各的令牌，续轮间隙不会串轮。
     */
    private static final Map<String, AtomicBoolean> RUNNING = new ConcurrentHashMap<>();

    /**
     * 后台结果送达时会话正忙（抢锁失败）的补偿标记：结果落在当轮历史快照之后，没人消化。
     * add 先于 tryAcquire 消除间隙竞态——抢锁失败标记留在册，本轮链释放时若会话已空闲
     * 就补一轮让模型看到；用户主动停止的链丢弃标记（结果已在历史里，随下一条用户消息走）
     */
    private static final Set<String> PENDING_BG_DELIVERY = ConcurrentHashMap.newKeySet();

    /**
     * 工具执行阶段的等待上限：审批等待不计入（那个靠用户点击/停止结束），只防工具 I/O 卡死拖住整轮
     */
    private static final long TOOL_EXECUTE_TIMEOUT_SECONDS = 60;

    /**
     * 压缩轮后的续轮指令：压缩轮的响应是摘要而非对用户问题的回答，且水位线裁剪后请求里
     * 已没有 user 轮（[摘要]是纯 assistant 历史——Anthropic 直接 400，OpenAI 没有指令可
     * 执行）。合成这条 user 消息驱动续轮，正常落库（UI 可见、历史良构）
     */
    private static final String CONTEXT_CONTINUE_PROMPT = "上下文已完成压缩，请基于摘要继续之前的任务。";

    private static final AppSettings appSettings = AppSettings.get();

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(Main.class);

    private static Window window;

    /**
     * conversationId -> (messageId -> ToolWriter)
     */
    private Map<String, Map<String, ToolWriter>> toolWriters = new ConcurrentHashMap<>();
    private Map<String, AiWriter> aiWriters = new ConcurrentHashMap<>();

    public SwingImp() {
        SwingUtilities.invokeLater(() -> {
            window = new Window("Agent");
            // 尺寸/位置/分隔条由 Window 从 AppSettings 恢复（无记录回落居中），关闭行为 Window 自己接管
            window.setVisible(true);

            // 回调触发时本轮用户消息已入库（Window.submitInput 先画卡再回调），
            // 历史快照直接取整个会话，无需单独传问题文本
            window.onInputSubmitted((conversation, text) -> startAgentLoop(conversation, false));

            // 重新生成：先抢会话锁再删旧回答，然后带全量历史重新请求
            window.onRegenerate(conversation -> startAgentLoop(conversation, true));

            window.onStopRequested(() -> {
                // 停的是当前显示的会话：通知那轮链中断；审批等待 200ms 内退出，已生成部分照常落库
                AtomicBoolean token = RUNNING.get(window.getStore().getActive().getId());
                if (token != null) {
                    token.set(true);
                    // 已派发的工具还在后台跑完才收尾（P2-5）：胶囊上说明状态，避免用户以为卡死连点。
                    // 纯文案不改停止语义；链结束 setConversationGenerating(false) 会把胶囊刷回"就绪"
                    window.setRealtimeInfo("正在等待工具收尾…");
                    LOG.info("停止生成......");
                }
            });

            window.setBackGroundTaskControl(new Window.BackGroundTaskControl() {
                @Override
                public boolean kill(String conversationId, String callId) {
                    TaskHandle killed = ShutDownJob.kill(callId);
                    if (killed != null && killed.background()) {
                        SwingUtilities.invokeLater(() ->
                                window.onBackGroundTaskKilled(conversationId, callId));
                    }
                    return killed != null;
                }

                @Override
                public String output(String conversationId, String callId) {
                    return ShutDownJob.getBufferedOutput(callId);
                }
            });
        });
    }


    @Override
    public void setGenerating(String sessionId, boolean generating) {
        ensureSessionId(sessionId);
        SwingUtilities.invokeLater(() -> window.setConversationGenerating(sessionId, false));
    }

    private Conversation ensureSessionId(String sessionId) {
        Conversation result;
        if ((result = window.getStore().getConversation(sessionId)) == null) {
            throw new IllegalArgumentException("conversation does not exist");
        }
        return result;
    }

    @Override
    public AiWriter appendAi(String sessionId, String messageId) {
        ensureSessionId(sessionId);
        AiWriterImp aiWriterImp = new AiWriterImp(sessionId);
        aiWriters.computeIfPresent(sessionId, (key, old) -> {
            old.finish();
            return aiWriterImp;
        });
        return aiWriterImp;
    }

    @Override
    public ToolWriter appendToolCall(String sessionId, String messageId, String toolName, String callId) {
        ensureSessionId(sessionId);
        Map<String, ToolWriter> messageIdWriters = toolWriters.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>());
        ToolWriter toolWriterImp;
        if (messageId == null || messageId.isEmpty()) {
            toolWriterImp = new ToolWriterImp(sessionId, toolName, callId);
            messageIdWriters.put(toolWriterImp.getMessageId(), toolWriterImp);
        } else {
            toolWriterImp = messageIdWriters.get(messageId);
            if (toolWriterImp == null) {
                throw new IllegalArgumentException("message id not found");
            }
        }
        return toolWriterImp;
    }

    @Override
    public void appendUser(String sessionId, String message) {
        ensureSessionId(sessionId);
        SwingUtilities.invokeLater(() -> window.addUserMessage(message));
    }

    @Override
    public void usage(String sessionId, UsageView usageView) {
        Conversation conversation = ensureSessionId(sessionId);
        if (usageView == null) {
            return;
        }
        conversation.setUsage(new Conversation.Usage(usageView.inputTokens(), usageView.outputTokens(), usageView.elapsedSeconds()));
    }

    @Override
    public void notify(String sessionId, NoticeLevel level, String text) {
        SwingUtilities.invokeLater(() -> window.addNotifyMessage(sessionId, text));
    }

    @Override
    public Boolean requestApproval(String sessionId, String messageId, String callId,
                                   HostRequests.ApprovalPayload payload, boolean additionalRequest,
                                   long deadlineEpochMs) {

        return null;
    }

    @Override
    public boolean onBackgroundDone(String sessionId, String callId, String output) {
        return false;
    }

    @Override
    public void onBackgroundKilled(String sessionId, String callId) {

    }

    public static class AiWriterImp implements AiWriter {

        private final Window.AiStream aiStream;

        public AiWriterImp(String sessionId) {
            aiStream = window.addAiMessage(sessionId, "");
        }

        @Override
        public void chunk(String text) {
            aiStream.appendChunk(text);
        }

        @Override
        public void thinking(String text) {
            aiStream.appendThinking(text);
        }

        @Override
        public void finishThinking() {
            aiStream.finishThinking();
        }

        @Override
        public void finish() {
            aiStream.finish();
        }
    }

    public static class ToolWriterImp implements ToolWriter {

        private final Window.ToolCallHandle handle;

        public ToolWriterImp(String sessionId, String toolName, String callId) {
            this.handle = window.addToolCall(sessionId, toolName, "", callId);
        }

        @Override
        public void args(String delta) {
            handle.appendArgs(delta);
        }

        @Override
        public void executing() {
            handle.markExecuting();
        }

        @Override
        public void finish(String result, boolean success, String arguments) {
            handle.finish(result, success, arguments);
        }

        @Override
        public String getMessageId() {
            return handle.getMessageId();
        }
    }
}

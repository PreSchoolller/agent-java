package io.github.preschoolller.agentjava.frontend;

import io.github.preschoolller.agentjava.core.enums.ToolState;
import io.github.preschoolller.agentjava.core.events.GapDetected;
import io.github.preschoolller.agentjava.core.events.HostRequestOpened;
import io.github.preschoolller.agentjava.core.events.MessageAppended;
import io.github.preschoolller.agentjava.core.events.MessageCompleted;
import io.github.preschoolller.agentjava.core.events.MessageDelta;
import io.github.preschoolller.agentjava.core.events.Notice;
import io.github.preschoolller.agentjava.core.events.NoticeLevel;
import io.github.preschoolller.agentjava.core.events.RoundCancelled;
import io.github.preschoolller.agentjava.core.events.RoundFailed;
import io.github.preschoolller.agentjava.core.events.RoundFinished;
import io.github.preschoolller.agentjava.core.events.RoundStarted;
import io.github.preschoolller.agentjava.core.events.SessionCreated;
import io.github.preschoolller.agentjava.core.events.SessionPinned;
import io.github.preschoolller.agentjava.core.events.SessionRemoved;
import io.github.preschoolller.agentjava.core.events.SessionRenamed;
import io.github.preschoolller.agentjava.core.events.StateEvent;
import io.github.preschoolller.agentjava.core.events.ToolStateChanged;
import io.github.preschoolller.agentjava.core.events.UserMessageRejected;
import io.github.preschoolller.agentjava.core.interfaces.AgentApp;
import io.github.preschoolller.agentjava.core.interfaces.EventListener;
import io.github.preschoolller.agentjava.core.interfaces.HostRequests;
import io.github.preschoolller.agentjava.frontend.gui.Window;

import javax.swing.SwingUtilities;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 前端渲染器：只消费 {@link AgentApp} 的事件流，只调 {@link AgentApp} 的命令。
 *
 * <p>不认识 core 的任何实现类，也不写会话仓库——需要落库的内容由 core 写完，
 * 再以事件或投影送达。所有 Window 调用统一切 EDT。
 *
 * <p>当前覆盖：消息上屏与增量、工具卡、审批、生成态、用量、通知、错误。
 * 会话侧栏（创建/切换/重命名/删除/置顶/分叉）仍走 Window 自己的会话列表，
 * 等 core 的 {@code snapshot()/session()} 接进侧栏后再切换数据源。
 */
public final class SwingRenderer implements EventListener {

    private final Window window;
    /** 审批回填用；null = 内核实现尚未就绪，整个前端只读 */
    private final AgentApp app;

    private final Map<String, Window.AiStream> aiStreams = new ConcurrentHashMap<>();
    private final Map<String, Window.ToolCallHandle> toolCards = new ConcurrentHashMap<>();
    /** 会话内 callId → messageId：审批事件只带 callId，得先换回卡片 */
    private final Map<String, String> callIds = new ConcurrentHashMap<>();

    public SwingRenderer(Window window, AgentApp app) {
        this.window = window;
        this.app = app;
    }

    @Override
    public void onEvent(StateEvent event) {
        try {
            dispatch(event);
        } catch (Throwable throwable) {
            // 渲染问题不能打断事件分发线程：吞掉并提示，后续事件继续
            later(() -> window.showNotice(event.sessionId(), "界面渲染异常：" + throwable));
        }
    }

    private void dispatch(StateEvent event) {
        if (event instanceof MessageAppended appended) {
            appended(appended);
        } else if (event instanceof MessageDelta delta) {
            delta(delta);
        } else if (event instanceof MessageCompleted completed) {
            // 句柄的注册发生在 EDT，收尾也必须排进同一队列，否则可能先 remove 后 put
            later(() -> {
                Window.AiStream stream = aiStreams.remove(key(completed.sessionId(), completed.messageId()));
                if (stream != null) {
                    stream.finish();
                }
            });
        } else if (event instanceof ToolStateChanged changed) {
            toolState(changed);
        } else if (event instanceof HostRequestOpened opened) {
            approval(opened);
        } else if (event instanceof RoundStarted started) {
            later(() -> window.setConversationGenerating(started.sessionId(), true));
        } else if (event instanceof RoundFinished finished) {
            later(() -> {
                if (finished.usage() != null) {
                    window.setUsageInfo(ViewAdapter.usageLabel(finished.usage()));
                }
                if (finished.chainEnded()) {
                    window.setConversationGenerating(finished.sessionId(), false);
                }
            });
        } else if (event instanceof RoundFailed failed) {
            later(() -> {
                window.setConversationGenerating(failed.sessionId(), false);
                window.showNotice(failed.sessionId(), "生成失败：" + failed.reason());
            });
        } else if (event instanceof RoundCancelled cancelled) {
            later(() -> window.setConversationGenerating(cancelled.sessionId(), false));
        } else if (event instanceof UserMessageRejected rejected) {
            later(() -> window.showNotice(rejected.sessionId(), "消息未发送：" + rejected.reason()));
        } else if (event instanceof Notice notice) {
            later(() -> notice(notice));
        } else if (event instanceof SessionCreated || event instanceof SessionRemoved
                || event instanceof SessionRenamed || event instanceof SessionPinned) {
            later(window::refreshSessions);
        } else if (event instanceof GapDetected gap) {
            // 事件缺口：当前只提示。接侧栏/历史快照时在这里补一次 session()/snapshot() 全量拉取
            later(() -> window.showNotice(null,
                    "事件流缺口（since=" + gap.sinceSeq() + "，最早=" + gap.earliestSeq() + "），请重新打开会话"));
        }
        // SessionCreated/Removed/Renamed/Pinned、ConfigChanged、UserMessageConfirmed：
        // 侧栏与配置面板尚未接 core，先不进 UI；接的时候在这里加，不要绕过 AgentApp 直接读仓库
    }

    // ---------- 消息 ----------

    private void appended(MessageAppended appended) {
        String sessionId = appended.sessionId();
        Message message = ViewAdapter.message(appended.message());
        if (message == null) {
            return;
        }
        if (message.isToolCall()) {
            later(() -> {
                Window.ToolCallHandle handle = window.openToolCard(sessionId, message);
                toolCards.put(key(sessionId, message.messageId()), handle);
                if (message.callId() != null) {
                    callIds.put(key(sessionId, message.callId()), message.messageId());
                }
            });
        } else if (Message.ROLE_USER.equals(message.role())) {
            later(() -> window.showUserMessage(sessionId, message));
        } else {
            later(() -> aiStreams.put(key(sessionId, message.messageId()),
                    window.openAiCard(sessionId, message)));
        }
    }

    private void delta(MessageDelta delta) {
        boolean hasText = delta.textDelta() != null && !delta.textDelta().isEmpty();
        boolean hasThinking = delta.thinkingDelta() != null && !delta.thinkingDelta().isEmpty();
        if (!hasText && !hasThinking) {
            return;
        }
        // 句柄在 EDT 上注册：读取也进同一队列，保证 Appended 先于 Delta 生效
        later(() -> {
            Window.AiStream stream = aiStreams.get(key(delta.sessionId(), delta.messageId()));
            if (stream == null) {
                return;
            }
            if (hasThinking) {
                stream.appendThinking(delta.thinkingDelta());
            }
            if (hasText) {
                stream.appendChunk(delta.textDelta());
            }
        });
    }

    // ---------- 工具 ----------

    private void toolState(ToolStateChanged changed) {
        if (changed.state() == ToolState.BG_RUNNING) {
            return; // 提交后台时建的卡已经是这个态
        }
        boolean success = changed.state() == ToolState.OK || changed.state() == ToolState.BG_DONE;
        String result = changed.resultText() == null ? "" : changed.resultText();
        later(() -> {
            Window.ToolCallHandle handle = toolCards.get(key(changed.sessionId(), changed.messageId()));
            if (handle == null) {
                return;
            }
            if (changed.state() == ToolState.RUNNING) {
                handle.markExecuting();
            } else {
                handle.finish(result, success, null);
            }
        });
    }

    private void approval(HostRequestOpened opened) {
        if (app == null) {
            return;
        }
        HostRequests.Request request = opened.request();
        later(() -> {
            String messageId = callIds.get(key(opened.sessionId(), request.callId()));
            Window.ToolCallHandle handle = messageId == null ? null
                    : toolCards.get(key(opened.sessionId(), messageId));
            if (handle == null) {
                // 找不到卡片（会话已切走/卡还没建）：当成拒绝，别让 core 挂到超时
                app.answer(request.requestId(), new HostRequests.Reject("no card"));
                return;
            }
            CompletableFuture<Boolean> decision = new CompletableFuture<>();
            decision.whenComplete((approved, error) -> app.answer(request.requestId(),
                    approved != null && approved ? new HostRequests.Approve()
                            : new HostRequests.Reject(error == null ? "user" : "cancelled")));
            handle.requestApproval(decision);
        });
    }

    // ---------- 通知 ----------

    private void notice(Notice notice) {
        String text = notice.level() == NoticeLevel.ERROR ? "Error: " + notice.text() : notice.text();
        window.showNotice(notice.sessionId(), text);
    }

    // ---------- 线程 ----------

    private static void later(Runnable action) {
        SwingUtilities.invokeLater(action);
    }

    private static String key(String sessionId, String messageId) {
        return sessionId + "/" + messageId;
    }
}

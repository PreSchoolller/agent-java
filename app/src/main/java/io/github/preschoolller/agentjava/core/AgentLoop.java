package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.backend.context.ContextHandlerManager;
import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.core.converter.RecordConverter;
import io.github.preschoolller.agentjava.core.enums.MessageState;
import io.github.preschoolller.agentjava.core.enums.Role;
import io.github.preschoolller.agentjava.core.enums.SessionStatus;
import io.github.preschoolller.agentjava.core.enums.ToolState;
import io.github.preschoolller.agentjava.core.events.CloseReason;
import io.github.preschoolller.agentjava.core.events.HostRequestClosed;
import io.github.preschoolller.agentjava.core.events.MessageAppended;
import io.github.preschoolller.agentjava.core.events.MessageCompleted;
import io.github.preschoolller.agentjava.core.events.MessageDelta;
import io.github.preschoolller.agentjava.core.events.Notice;
import io.github.preschoolller.agentjava.core.events.NoticeLevel;
import io.github.preschoolller.agentjava.core.events.RoundCancelled;
import io.github.preschoolller.agentjava.core.events.RoundCause;
import io.github.preschoolller.agentjava.core.events.RoundFailed;
import io.github.preschoolller.agentjava.core.events.RoundFinished;
import io.github.preschoolller.agentjava.core.events.RoundStarted;
import io.github.preschoolller.agentjava.core.events.StateEvent;
import io.github.preschoolller.agentjava.core.events.ToolArgsDelta;
import io.github.preschoolller.agentjava.core.events.ToolStateChanged;
import io.github.preschoolller.agentjava.core.events.UserMessageRejected;
import io.github.preschoolller.agentjava.core.interfaces.HostRequests;
import io.github.preschoolller.agentjava.core.records.MessageView;
import io.github.preschoolller.agentjava.core.records.ToolView;
import io.github.preschoolller.agentjava.core.records.UsageView;
import io.github.preschoolller.agentjava.core.repository.ConversationRepository;
import io.github.preschoolller.agentjava.spi.context.ContextHandler;
import io.github.preschoolller.agentjava.backend.provider.ProviderFactory;
import io.github.preschoolller.agentjava.spi.enums.AgentMode;
import io.github.preschoolller.agentjava.spi.provider.PromptParams;
import io.github.preschoolller.agentjava.spi.provider.records.ProviderStreamingResponse;
import io.github.preschoolller.agentjava.spi.enums.ReasoningEffortEnum;
import io.github.preschoolller.agentjava.spi.enums.RoleEnum;
import io.github.preschoolller.agentjava.backend.provider.common.ThreadPoolFactory;
import io.github.preschoolller.agentjava.backend.provider.common.ToolCall;
import io.github.preschoolller.agentjava.spi.provider.event.BaseEvent;
import io.github.preschoolller.agentjava.spi.provider.event.FunctionToolCallDeltaEvent;
import io.github.preschoolller.agentjava.spi.provider.event.FunctionToolCallDoneEvent;
import io.github.preschoolller.agentjava.spi.provider.event.FunctionToolCallStartEvent;
import io.github.preschoolller.agentjava.spi.provider.event.PoisonEvent;
import io.github.preschoolller.agentjava.spi.provider.event.ReasoningDeltaDoneEvent;
import io.github.preschoolller.agentjava.spi.provider.event.ReasoningDeltaEvent;
import io.github.preschoolller.agentjava.spi.provider.event.ReasoningDeltaStartEvent;
import io.github.preschoolller.agentjava.spi.provider.event.ResponseCompleteEvent;
import io.github.preschoolller.agentjava.spi.provider.event.ResponseDeltaDoneEvent;
import io.github.preschoolller.agentjava.spi.provider.event.ResponseDeltaEvent;
import io.github.preschoolller.agentjava.spi.provider.event.ResponseDeltaStartEvent;
import io.github.preschoolller.agentjava.spi.provider.event.ErrorMessageEvent;
import io.github.preschoolller.agentjava.spi.provider.Provider;
import io.github.preschoolller.agentjava.core.repository.AppSettings;
import io.github.preschoolller.agentjava.core.repository.Conversation;
import io.github.preschoolller.agentjava.core.repository.Message;
import io.github.preschoolller.agentjava.core.repository.Profile;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentLoop {

    /**
     * 会话级 agent 循环锁（id → 本轮链的取消令牌）：不同会话各自并行，同一会话同一时刻最多一轮。
     * UI 的生成态置灰是第一道，这里是后端兜底；令牌随工具续轮传递，整条链结束才移除条目。
     * 各会话各拿各的令牌，续轮间隙不会串轮。
     */
    private final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    /** 后台结果送达时会话正忙（抢锁失败）的补偿标记：结果落在当轮历史快照之后，没人消化。
     *  add 先于 tryAcquire 消除间隙竞态——抢锁失败标记留在册，本轮链释放时若会话已空闲
     *  就补一轮让模型看到；用户主动停止的链丢弃标记（结果已在历史里，随下一条用户消息走） */
    private final Set<String> pendingBgDelivery = ConcurrentHashMap.newKeySet();

    /** 工具执行阶段的等待上限：审批等待不计入（那个靠用户点击/停止结束），只防工具 I/O 卡死拖住整轮 */
    private static final long TOOL_EXECUTE_TIMEOUT_SECONDS = 60;

    /** 压缩轮后的续轮指令：压缩轮的响应是摘要而非对用户问题的回答，且水位线裁剪后请求里
     *  已没有 user 轮（[摘要]是纯 assistant 历史——Anthropic 直接 400，OpenAI 没有指令可
     *  执行）。合成这条 user 消息驱动续轮，正常落库（UI 可见、历史良构） */
    private static final String CONTEXT_CONTINUE_PROMPT = "上下文已完成压缩，请基于摘要继续之前的任务。";

    private final AppSettings appSettings = AppSettings.get();

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AgentLoop.class);

    private final ConversationRepository repo;
    private final EventPublisher eventPublisher;
    private final HostRequestRegistry hostRequestRegistry;

    public AgentLoop(ConversationRepository conRepo, EventPublisher eventPublisher, HostRequestRegistry hostRequestRegistry) {
        repo = conRepo;
        this.eventPublisher = eventPublisher;
        this.hostRequestRegistry = hostRequestRegistry;
    }

    public void submit(Conversation conversation, String userInput, boolean regenerate) {
        if (userInput == null || userInput.isBlank()) {
            eventPublisher.publish(seq -> new UserMessageRejected(conversation.getId(), null, "blank input", seq));
            return;
        }
        if (!regenerate) {
            addUserMessage(repo, conversation, eventPublisher, userInput);
        }
        startAgentLoop(conversation, regenerate, true);
    }

    public Map<String, SessionStatus> getSessionStatus(List<Conversation> conversations) {
        Map<String, SessionStatus> sessionStatusMap = new HashMap<>();
        for (Conversation conversation : conversations) {
            if (conversation == null) {
                continue;
            }
            AtomicBoolean atomicBoolean = running.get(conversation.getId());
            sessionStatusMap.put(conversation.getId(), atomicBoolean == null ? SessionStatus.IDLE : SessionStatus.RUNNING);
        }
        return sessionStatusMap;
    }

    public void cancel(String conversationId) {
        LOG.info("cancelling conversation id {}", conversationId);
        AtomicBoolean atomicBoolean = running.get(conversationId);
        if (atomicBoolean == null) {
            return;
        }
        atomicBoolean.set(true);
    }

    private static void addUserMessage(ConversationRepository repo, Conversation conversation,
                                       EventPublisher eventPublisher, String userInput) {
        String conversationId = conversation.getId();
        eventPublisher.publish(seq -> {
            Message userMessage = repo.appendMessage(conversationId, Message.Role.ROLE_USER, userInput);
            return new MessageAppended(conversationId,
                    buildMessageView(conversationId, userMessage, MessageState.CONFIRMED), seq);
        });
    }

    /** 抢会话锁：抢到返回本链的取消令牌，该会话已在跑返回 null */
    private AtomicBoolean tryAcquire(String conversationId) {
        AtomicBoolean token = new AtomicBoolean(false);
        return running.putIfAbsent(conversationId, token) == null ? token : null;
    }

    private void startAgentLoop(Conversation conversation, boolean regenerate, boolean submit) {
        AtomicBoolean token = tryAcquire(conversation.getId());
        if (token == null) {
            return; // 该会话已在生成：正常情况 UI 已拦，这里是后端兜底
        }
        if (regenerate) {
            repo.removeLastMessage(conversation.getId());
        }
        LoopConfig loopConfig;
        try {
            loopConfig = buildLoopConfig(conversation);
        } catch (Throwable throwable) {
            running.remove(conversation.getId(), token);
            LOG.error("prepare snapshot params failed: ", throwable);
            eventPublisher.publish(seq -> new Notice(conversation.getId(), NoticeLevel.ERROR, throwable.getMessage(), seq));
            return;
        }
        // 链配置在此冻结；每轮消息快照在 loop 内取，后台结果补偿续链在 loop 尾部另起
        RoundCause roundCause = regenerate ? RoundCause.REGENERATE : (submit ? RoundCause.SUBMIT : RoundCause.BACKGROUND);
        eventPublisher.publish(seq -> new RoundStarted(conversation.getId(), roundCause, seq));
        loop(conversation, token, loopConfig);
    }

    /**
     * 整条 agent 链（后台线程跑）：配置在 startAgentLoop 冻结为 LoopConfig，每轮经
     * snapshotMessages 重取历史，doContextManage 压缩决策后 appendUiMessages 统一落
     * UI 消息（标记+续轮 user+AI 占位卡一个 invokeAndWait 内完成）再 doStream；
     * 工具结果到齐由 doStream requestContinue 续轮（同一个取消令牌、同一把会话锁，生成态保持到整条链结束），
     * 压缩轮后由 requestContinue 强制补一轮续轮（合成 user 消息驱动，见 CONTEXT_CONTINUE_PROMPT）；
     * 无续轮诉求时链收束：清生成态、放会话锁，再查后台结果补偿标记决定是否补一轮。
     * 生成的会话不必在前台：卡片经会话 id 定向落库
     */
    private void loop(Conversation conversation, AtomicBoolean cancelled, LoopConfig loopConfig) {
        CompletableFuture.runAsync(() -> {
            PrepareParams prepareParams;
            LoopControl loopControl = new LoopControl();
            boolean justCompressed = false;
            long start = System.currentTimeMillis();
            // 这里是唯一调用 doStream 的地方。停止后整条链不再发任何新请求：
            // 普通续轮由 doStream requestContinue 驱动（停止后不会 requestContinue），
            // 压缩轮的强制续轮在轮末 requestContinue，下一轮判 cancelled 拦住
            while (loopControl.hasMoreRounds() && !cancelled.get()) {
                int round = loopControl.nextRound();
                try {
                    prepareParams = appendMessages(conversation, loopConfig); // 每轮快照：只重取历史，配置不动（建卡统一在 appendUiMessages）
                    boolean needCompress = doContextManage(conversation, prepareParams, loopConfig, justCompressed);
                    AiMessage aiMessage = appendUiMessages(conversation, needCompress);
                    prepareParams = new PrepareParams(prepareParams.provider, aiMessage, prepareParams.paramsBuilder);
                    doStream(conversation, cancelled, prepareParams, loopControl);
                    if (needCompress && !loopControl.hasMoreRounds()) {
                        // 压缩轮后强制补一轮续轮：走 requestContinue 正常计数，不能靠 while 条件
                        // 旁路进门——续轮 executed 会反超 requested，续轮里的工具调用再
                        // requestContinue 只是把缺口补平，hasMoreRounds 仍为 false，链提前收束
                        loopControl.requestContinue();
                    }
                    justCompressed = needCompress;
                } catch (Throwable e) {
                    LOG.error("agent loop exception", e);
                    eventPublisher.publish(seq -> new RoundFailed(conversation.getId(), e.getMessage(), seq));
                    break; // 快照/流式异常：放弃续轮，避免失败快照反复重试
                }
            }
            // 双参 remove：条目还是本轮令牌才清（防御性的对称）
            running.remove(conversation.getId(), cancelled);
            long end = System.currentTimeMillis();
            long elapsedSeconds = (end - start) / 1000;
            if (cancelled.get()) {
                eventPublisher.publish(seq -> new RoundCancelled(conversation.getId(), seq));
            }
            eventPublisher.publish(seq -> new RoundFinished(conversation.getId(),
                    buildUsageView(conversation, elapsedSeconds), true, seq));
            LOG.info("loop of {} ends after {} rounds, elapsed {}s", conversation.getId(), loopControl.executed, elapsedSeconds);
            // 后台结果补偿（顺序关键：先放锁再查标记——bg 回调抢锁失败必然发生在放锁之前，
            // 本查必然能看见它留的标记）。生成期间送达的结果在链释放、会话已空闲时补一轮
            // 让模型消化；用户主动停止的链丢弃标记（结果已在历史里，随下一条用户消息自然带上）
            if (pendingBgDelivery.remove(conversation.getId()) && !cancelled.get()) {
                startAgentLoop(conversation, false, false);
                // 抢锁失败：新链已持有会话，其快照必然包含该结果（注入先于标记），不会漏消化
            }
        });
    }

    private static UsageView buildUsageView(Conversation conversation, double elapsedSeconds) {
        Conversation.Usage usage = conversation.getUsage();
        if (usage == null) {
            usage = new Conversation.Usage(0, 0, elapsedSeconds);
        }
        return new UsageView(usage.inputTokens(), usage.outputTokens(), elapsedSeconds);
    }

    private AiMessage appendUiMessages(Conversation conversation, boolean needCompress) {
        if (needCompress) {
            repo.appendContextMarker(conversation.getId());
            addUserMessage(repo, conversation, eventPublisher, CONTEXT_CONTINUE_PROMPT);
        }
        return new AiMessage(conversation.getId());
    }

    /**
     * 上下文管理：needCompress 命中（由选中的可修改处理器决策）时返回 true，落库交给调用方
     * appendUiMessages（invokeAndWait 内依次追加 标记→续轮 user 消息→AI 占位卡，标记天然排在
     * 占位卡前，摘要填进占位卡后就落在标记后，下轮裁剪保留摘要）。并把标记补记进本轮窗口——
     * 插件收到的快照以标记收尾，压缩范围就此圈定（上个标记+1 → 本标记）。handle 的返回值整体
     * 替换本轮发出的消息列表；标记在 handle 正常返回后才落库：插件抛异常时本方法直接上抛、
     * appendUiMessages 不执行，水位线不推进，下一轮仍是全窗口可重试压缩，避免"标记已落但
     * 压缩结果未生成"把没压过的内容裁掉。
     * justCompressed=true（上轮刚压缩的续轮）：跳过压缩决策（usage 还是压缩轮响应的，
     * 不归零会立刻再次触发），handle 照常跑（只读记录不缺轮），但返回值不替换本轮消息。
     */
    private static boolean doContextManage(Conversation conversation, PrepareParams prepareParams, LoopConfig loopConfig, boolean justCompressed) {
        ArrayList<PromptParams.RoleMessage> roleMessages = new ArrayList<>(prepareParams.paramsBuilder.build().getRoleMessages());
        Conversation.Usage usage = conversation.getUsage();
        long inputTokens = (usage == null || justCompressed) ? 0 : usage.inputTokens();
        long outputTokens = (usage == null || justCompressed) ? 0 : usage.outputTokens();
        ContextHandler.ContextDetail contextDetail = new ContextHandler.ContextDetail(roleMessages, inputTokens, outputTokens,
                loopConfig.modelCtxWindow, loopConfig.mHandlerName);
        boolean needCompress = !justCompressed && ContextHandlerManager.needCompress(contextDetail);
        if (needCompress) {
            // contextDetail 补记标记（needCompress 的判定窗口不含标记——决策看的是真实内容）。
            // 标记用 CONTEXT_MARKER 角色：插件按角色识别边界并在返回时剥掉；就算劣质插件
            // 原样透传，provider 序列化对它也是静默跳过，不会污染请求
            roleMessages.add(new PromptParams.RoleMessage(RoleEnum.CONTEXT_MARKER, ContextHandler.MARKER_CONTENT));
            LOG.info("need context compress--------++++++++++----------+++++++++------------++++++++++--------++++++++----------");
        }
        ContextHandler.ContextManaged contextManaged = ContextHandlerManager.handle(contextDetail);
        if (justCompressed) {
            return false;
        }
        prepareParams.paramsBuilder.clearRoleMessages();
        contextManaged.roleMessages().forEach(prepareParams.paramsBuilder::roleMessages);
        return needCompress;
    }

    /**
     * 冻结整条链的请求构造参数：模型/方案路由（key、backendUrl）/思考强度/权限模式/
     * 工具清单/系统提示词，链中途一律不重查——设置变更对下一条链生效，不改动跑着的链
     * （主流 agent 语义：一次 run = 冻结的构造参数 + 演进的消息）。也别在这里读历史/建卡，
     * 那是每轮快照（appendMessages）的事。
     */
    private LoopConfig buildLoopConfig(Conversation conversation) {
        // 后台线程只读这份不可变数据
        String model = conversation.getModelName();
        String selectedProfile = conversation.getModelProfile();
        Profile profile = selectedProfile == null ? null : appSettings.getProfile(selectedProfile);
        if (profile == null) {
            profile = appSettings.findProfileOwningModel(model); // 下拉没带所属方案时按模型名反查
        }
        if (profile == null) {
            // 模型不属于任何方案（理论不可达）的最后兜底：第一个方案（load 保证至少一个）
            profile = appSettings.getProfile(appSettings.getProfileNames().get(0));
        }
        if (model == null || model.isBlank()) {
            // 外部注入（dsh-shim）新建的会话没有模型戳：回落方案默认模型。
            // 不兜底 provider 会拿到 null model 直接炸（OpenAI SDK 非 null 校验），她的首轮即崩
            model = profile.getModel();
            if (model == null || model.isBlank()) {
                List<String> models = profile.getModels();
                model = (models == null || models.isEmpty()) ? "default" : models.get(0);
            }
        }
        Provider provider = ProviderFactory.createProvider(profile.getApiType());
        // 思考强度/权限模式读会话记忆；旧会话没盖过戳（字段 null）回落输入区当前值
        ReasoningEffortEnum re = conversation.getReasoningEffort() != null
                ? conversation.getReasoningEffort() : ReasoningEffortEnum.ULTRA;
        AgentMode mode = conversation.getAgentMode() != null
                ? conversation.getAgentMode() : AgentMode.EDIT;
        // 纯聊天不挂工具，工作区无所谓（保持 null：不建目录、系统提示词不注入）
        String workspace = AgentMode.CHAT == mode ? null : resolveWorkspace(conversation);
        String key = profile.getKey();
        String backendUrl = profile.getBackendUrl();
        String systemPrompt = profile.getSystemPrompt();
        int modelCtxWindow = profile.getModelCtxWindow(model);
        List<String> enabledTools = appSettings.getEnabledTools();
        String mHandlerName = appSettings.getActiveContextHandler();
        PromptParams.Builder paramsBuilder = PromptParams.builder().model(model).re(re)
                .agentMode(mode).workspace(workspace).key(key).backendUrl(backendUrl)
                .systemPrompt(systemPrompt).enabledTools(enabledTools);
        return new LoopConfig(provider, paramsBuilder.build(), modelCtxWindow, mHandlerName);
    }

    /**
     * 装配单轮请求：重取历史快照 + 建本轮 AI 占位消息，与冻结的 LoopConfig 合成。
     * 快照即消费：补偿标记的清除必须和读历史在**同一个任务**里——消费挪到 loop 体
     * （bg 线程）时，回调任务（注入结果→加标记）可以插在 remove 和快照之间，读到了结果
     * 却留下标记，链尾补偿再把同一结果消化一遍（幽灵轮）；
     * whenBackgroundTaskDone 先记再抢，抢到锁的链的快照负责消化（对称收口）
     */
    private PrepareParams appendMessages(Conversation conversation, LoopConfig loopConfig) {
        pendingBgDelivery.remove(conversation.getId());
        List<PromptParams.RoleMessage> history = buildRoleMessages(conversation, loopConfig);
        PromptParams.Builder builder = loopConfig.noMessages.toBuilder();
        builder.clearRoleMessages();
        for (PromptParams.RoleMessage roleMessage : history) {
            builder.roleMessages(roleMessage);
        }
        return new PrepareParams(loopConfig.provider, null, builder);
    }

    /** 链级冻结配置：provider 实例 + 不含消息的请求模板（PromptParams.Builder(PromptParams) 拷贝列表，
     *  toBuilder 出来的 builder 每轮独立改写消息，不会污染这里）。整条链只读 */
    private record LoopConfig(Provider provider, PromptParams noMessages, int modelCtxWindow, String mHandlerName) {
    }

    private record PrepareParams(Provider provider, AiMessage aiMessage, PromptParams.Builder paramsBuilder) {
    }

    /**
     * 一轮流式回复（agent 循环的单轮）：后台线程从事件队列取增量 → 回填 →
     * 队列毒丸后等工具收尾，有工具结果就 requestContinue 续下一轮（同一个取消令牌、同一把会话锁）。
     */
    private void doStream(Conversation conversation, AtomicBoolean cancelled, PrepareParams prepareParams, LoopControl loopControl) {
        BlockingQueue<BaseEvent> events = null;
        AtomicBoolean shouldStopBackend = null;
        List<CompletableFuture<Void>> toolCalls = new ArrayList<>();
        Map<String, ToolCallHandle> callIdHandleMap = new HashMap<>();
        Map<String, String> callIdByItemId = new HashMap<>();
        Map<String, StringBuilder> argsByCallId = new HashMap<>();
        Set<String> doneCalls = new HashSet<>();
        Map<String, ToolCall> indexToolCall = new HashMap<>();
        AiMessage aiMessage = prepareParams.aiMessage;
        try {
            PromptParams promptParams = prepareParams.paramsBuilder.build();
            long roundStartNanos = System.nanoTime(); // 本轮耗时（含 HTTP 建流），胶囊上展示用
            ProviderStreamingResponse providerStreamingResponse = prepareParams.provider
                    .sendPromptStreaming(promptParams, ThreadPoolFactory.getResponseExecutor());
            events = providerStreamingResponse.blockingQueue();
            shouldStopBackend = providerStreamingResponse.shouldStop();
            BaseEvent event;
            while (!cancelled.get() && !((event = events.take()) instanceof PoisonEvent)) {
                if (event instanceof ReasoningDeltaStartEvent) {
                    // 思考走前端卡片上的独立思考区，开始信号不必再拼进正文
                } else if (event instanceof ReasoningDeltaEvent deltaEvent) {
                    appendThinkingChunk(deltaEvent.delta(), aiMessage, cancelled);
                } else if (event instanceof ReasoningDeltaDoneEvent) {
                    finishThinkingQuietly(aiMessage, cancelled);
                } else if (event instanceof ResponseDeltaStartEvent) {
                    appendAiMessage("", aiMessage, cancelled);
                } else if (event instanceof ResponseDeltaEvent deltaEvent) {
                    appendAiMessage(deltaEvent.delta(), aiMessage, cancelled);
                } else if (event instanceof ResponseDeltaDoneEvent) {
                    appendAiMessage("", aiMessage, cancelled);
                } else if (event instanceof FunctionToolCallStartEvent e) {
                    addToolCall(conversation.getId(), e.name(), e.callId(), callIdHandleMap);
                    callIdByItemId.put(e.id(), e.callId());
                    argsByCallId.put(e.callId(), new StringBuilder());
                    ToolCall toolCall = new ToolCall();
                    toolCall.setTool(ToolManager.findByName(e.name()));
                    toolCall.setArguments("");
                    toolCall.setCallId(e.callId());
                    indexToolCall.put(e.id(), toolCall);
                } else if (event instanceof FunctionToolCallDeltaEvent e) {
                    // 参数增量：事件带的是条目 id，用 Start 事件建立的映射换回 callId 找卡片
                    String callId = callIdByItemId.get(e.id());
                    ToolCallHandle handle = callId == null ? null : callIdHandleMap.get(callId);
                    if (handle == null || cancelled.get()) {
                        continue;
                    }
                    StringBuilder buffered = argsByCallId.get(callId);
                    if (buffered != null) {
                        buffered.append(e.argsDelta()); // 本地累积（后台线程独写），给终态兜底
                    }
                    String delta = e.argsDelta();
                    handle.appendArgs(delta);
                } else if (event instanceof FunctionToolCallDoneEvent e) {
                    ToolCallHandle toolCallHandle = callIdHandleMap.get(e.callId());
                    ToolCall toolCall = indexToolCall.get(e.id());
                    if (toolCall == null) {
                        LOG.error("应该不可能");
                        continue;
                    }
                    toolCall.setArguments(argsByCallId.get(toolCall.getCallId()).toString());
                    toolCall.setApprovalGate((tc, additionalRequest) ->
                            requestToolApproval(tc, promptParams.getAgentMode(), callIdHandleMap, cancelled, additionalRequest));
                    toolCall.setWorkspace(promptParams.getWorkspace());
                    toolCall.setBackGroundTask((callId, output) -> whenBackgroundTaskDone(conversation,
                            callId, output, pendingBgDelivery, this, repo, eventPublisher));
                    if (toolCallHandle != null) {
                        toolCallHandle.markExecuting();
                    }
                    toolCalls.add(performToolCall(toolCall, toolCallHandle));
                    doneCalls.add(e.callId());
                } else if (event instanceof ResponseCompleteEvent complete) {
                    updateUsageInfo(conversation, cancelled, complete.inputTokens(), complete.outputTokens(), roundStartNanos);
                } else if (event instanceof ErrorMessageEvent error) {
                    // 流式错误（visitError/visitFailed/visitIncomplete/SSE 异常发射，后端接线待做）：
                    // 通知气泡展示，流本身照常走到毒丸收束
                    eventPublisher.publish(seq -> new Notice(conversation.getId(), NoticeLevel.ERROR, "Error: " + error.message(), seq));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("agent 流式轮被中断", e);
        } catch (RuntimeException e) {
            eventPublisher.publish(seq -> new Notice(conversation.getId(), NoticeLevel.ERROR, "Error: " + e.getMessage(), seq));
        } finally {
            aiMessage.finish(); // 停止也走 finish：已生成部分照常保留并落库
            if (cancelled.get() && shouldStopBackend != null) {
                shouldStopBackend.set(true);
            }
            for (String callId : callIdHandleMap.keySet()) {
                if (doneCalls.contains(callId)) {
                    continue;
                }
                StringBuilder buffered = argsByCallId.get(callId);
                String partialArgs = buffered == null ? "" : buffered.toString();
                String result = cancelled.get() ? "用户手动取消" : "Error: dangling function call without corresponding item done event";
                callIdHandleMap.get(callId).finish(result, false, partialArgs);
            }
            // run() 全路径兜底必达终态、执行超时有调度任务兜底，join 不会永久挂起
            toolCalls.forEach(CompletableFuture::join);
            if (cancelled.get()) {
                drainUntilPoison(events);
            }
            if (!cancelled.get() && !toolCalls.isEmpty()) {
                // 工具续轮：同一个取消令牌、同一把会话锁，生成态保持到整条链结束
                loopControl.requestContinue();
            }
            // 后台结果补偿不在本轮做：查标记必须发生在放锁之后（bg 回调抢锁失败必先于本查），
            // 见 loop 尾部
        }
    }

    private static void whenBackgroundTaskDone(Conversation conversation, String callId, String output,
                                               Set<String> PENDING_BG_DELIVERY, AgentLoop agentLoop,
                                               ConversationRepository repo, EventPublisher eventPublisher) {
        // bash 后台任务完成回调（bgTask 线程触发）：落库+广播（工具消息回填、注入用户消息），
        // 会话空闲（抢到锁）则自动续一轮让模型消化后台结果；生成中只落库并留补偿标记，
        // 本轮链释放时若已空闲会补一轮——结果落在快照之后也没人理的缝隙由这里兜住
        if (onBackGroundTaskDone(conversation, callId, output, repo, eventPublisher)) {
            String convId = conversation.getId();
            PENDING_BG_DELIVERY.add(convId); // 先记再抢：抢锁失败的间隙由链释放兜底；抢到了由快照对称清除
            agentLoop.startAgentLoop(conversation, false, false);
        }
    }

    public static boolean onBackGroundTaskDone(Conversation conversation, String callId, String output,
                                               ConversationRepository repo, EventPublisher eventPublisher) {
        String conversationId = conversation.getId();
        StateEvent event = eventPublisher.publish(seq -> {
            Message updated = repo.completeBackGroundTask(conversationId, callId, output);
            if (updated == null) {
                return null;
            }
            return new ToolStateChanged(conversationId, updated.messageId(), updated.callId(),
                    RecordConverter.toToolState(updated.toolStatus()), updated.text(), updated.toolArgs(), false, seq);
        });
        if (!(event instanceof ToolStateChanged changed)) {
            return false;
        }
        boolean killed = changed.state() == ToolState.BG_KILLED;
        String text = (killed ? "【后台任务已终止 id: " : "【后台任务完成 id: ") + callId + "】\n"
                + (output == null || output.isBlank() ? "(无输出)" : output.strip());
        addUserMessage(repo, conversation, eventPublisher, text);
        return true;
    }

    /**
     * 用量落到会话上（Conversation.Usage，会话级数据源）
     */
    private static void updateUsageInfo(Conversation conversation, AtomicBoolean cancelled,
                                        long inputTokens, long outputTokens, long roundStartNanos) {
        if (!cancelled.get() && (inputTokens > 0 || outputTokens > 0)) {
            double elapsedSeconds = (System.nanoTime() - roundStartNanos) / 1_000_000_000.0;
            Conversation.Usage usage = new Conversation.Usage(inputTokens, outputTokens, elapsedSeconds);
            conversation.setUsage(usage);
        }
    }

    /**
     * 审批钩子，可以在这里阻塞等用户点"允许/拒绝"。策略由 AgentMode 定：该问才问，不该问直接放行。
     * 等点击时每 200ms 醒来看一眼 cancelled——用户点停止不会把等待者吊死。
     */
    private static boolean requestToolApproval(ToolCall toolCall, AgentMode mode,
                                               Map<String, ToolCallHandle> callIdHandleMap,
                                               AtomicBoolean cancelled, boolean additionalRequest) {
        if (!mode.requiresApproval(toolCall.getTool()) && !additionalRequest) {
            return true;
        }
        if (additionalRequest && AgentMode.FULL_ACCESS.equals(mode)) {
            return true;
        }
        LOG.info("审批工具： {}， 是否额外申请：{}", toolCall.getTool().name(), additionalRequest);
        ToolCallHandle handle = callIdHandleMap.get(toolCall.getCallId());
        if (handle == null || cancelled.get()) {
            // 自动拒绝的三个分支之一。出现过"没人点却自动拒绝"（间歇性），每个分支留日志
            // 便于下次 occurrence 定位：卡片缺失 / 用户已停止 / 等待被中断
            LOG.info("审批自动拒绝：handle={} cancelled={}",
                    handle == null ? "缺失(callId=" + toolCall.getCallId() + ")" : "在册", cancelled.get());
            return false; // 没有卡片可挂 / 已经叫停：宁可拦下也不静默执行
        }
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        try {
            handle.requestApproval(decision);
            while (!cancelled.get()) {
                try {
                    return Boolean.TRUE.equals(decision.get(200, TimeUnit.MILLISECONDS));
                } catch (TimeoutException stillWaiting) {
                    // 用户还没点，继续等
                } catch (CancellationException rejected) {
                    LOG.info("审批自动拒绝：decision 被取消");
                    return false;
                } catch (ExecutionException impossible) {
                    LOG.info("审批自动拒绝：decision 异常完成", impossible);
                    return false; // 只有 complete(true/false)，正常到不了
                }
            }
            LOG.info("审批自动拒绝：用户点了停止");
            decision.cancel(true); // 用户点了停止：终结等待，provider 会把这次调用按未放行收尾
            return false;
        } catch (InterruptedException e) {
            LOG.info("审批自动拒绝：等待线程被中断");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void appendAiMessage(String chunk, AiMessage stream, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return; // 用户点了停止：后续增量丢弃，已生成部分由下面的 finish 落库
        }
        stream.appendContent(chunk);
    }

    /** 思考增量进前端独立思考区：投递 + 停止截流，与 appendAiMessage 同款模板 */
    private static void appendThinkingChunk(String delta, AiMessage stream, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return;
        }
        stream.appendThinking(delta);
    }

    /** ReasoningDeltaDone 通知前端收尾：思考区收起为"已思考 N 秒"（不调也行，正文增量会隐式收尾） */
    private static void finishThinkingQuietly(AiMessage stream, AtomicBoolean cancelled) {
        if (cancelled.get()) {
            return;
        }
        stream.finishThinking();
    }

    private void addToolCall(String conversationId, String name, String callId, Map<String, ToolCallHandle> callIdHandleMap) {
        ToolCallHandle toolCallHandle = new ToolCallHandle(conversationId, name, callId);
        callIdHandleMap.put(callId, toolCallHandle);
    }

    private static CompletableFuture<Void> performToolCall(ToolCall toolCall, ToolCallHandle toolCallHandle) {
        // 执行在 Done 事件侧启动：与 Start 同队列同线程消费且 addToolCall 同步登记，卡片句柄此刻必然在册
        ThreadPoolFactory.getToolCallExecutor().execute(toolCall);
        ToolCall.ToolCallStatus status = toolCall.getCombinedStatus();

        // 执行阶段超时：额外审批工具从 extraGatesDone 起算（审批等待不计入），其余从初审通过起算。
        // 一次性调度任务不占常驻线程；completeTerminally 先到先得，正常完成在先则这次写入无效
        CompletableFuture<Void> clockStart = toolCall.getTool().supportsAdditionalApproval()
                ? status.extraGatesDone() : status.executionStarted();
        clockStart.thenRun(() -> ThreadPoolFactory.getTimeoutScheduler().schedule(() ->
                        toolCall.completeTerminally(ToolCall.Status.INCOMPLETE,
                                "工具执行超时（" + TOOL_EXECUTE_TIMEOUT_SECONDS + " 秒未完成），已放弃等待；工具可能仍在后台执行。"),
                TOOL_EXECUTE_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        // 终态到达（完成/拒绝/超时/异常）→ 收尾消息；返回的 future 随终态完成，续轮 forEach(join) 由此收束
        return status.statusFinal().thenAccept(finalStatus -> {
            if (toolCallHandle != null) {
                toolCallHandle.finish(toolCall.getResult(),
                        ToolCall.Status.COMPLETE.equals(finalStatus), toolCall.getArguments());
            }
        });
    }

    /**
     * 会话绑定的工作区落定成绝对路径：没绑过的用默认（~/.agentjava/workspace），顺手把目录建出来
     * （没有 write 工具时用不上，但相对路径读它下面的文件前目录先在着总没错）。建失败只打日志，
     * 工具执行时会自然报错，不在这里拦请求。
     */
    private static String resolveWorkspace(Conversation conversation) {
        String workspace = conversation.getWorkspace();
        if (workspace != null && !workspace.isBlank()) {
            return workspace;
        }
        Path dir = AppSettings.defaultWorkspace();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("创建默认工作区失败: " + e.getMessage());
        }
        return dir.toString();
    }

    /**
     * 会话消息 → 后端轮次列表：空文本跳过，assistant 轮只发正文。
     * 思考内容存在 Message.thinking 独立字段，本来就不进 text，回传天然只带正文
     * （模型每轮重新思考，历史里的 CoT 是噪音，DeepSeek 等服务端甚至直接拒绝回传）。
     * 配置了可修改上下文处理器时按压缩水位线裁剪：只取最后一个标记（不包含）之后的
     * messages 段——标记之前的历史已由插件压缩（摘要以常规消息形态存于标记之后，或由
     * 插件自持），标记本身与更早的消息不进请求（后端 api 请求忽略压缩标记 message，
     * 这里是主裁剪口，provider 序列化还有一道 CONTEXT_MARKER 兜底）。未配置处理器时
     * 不裁剪："不启用"=全量历史，残留的旧标记（处理器后来被停用）不会裁掉内容。
     */
    private static List<PromptParams.RoleMessage> buildRoleMessages(Conversation conversation, LoopConfig loopConfig) {
        List<PromptParams.RoleMessage> roleMessages = new ArrayList<>();
        List<Message> messages = conversation.getMessages();
        int start = 0;
        if (loopConfig.mHandlerName() != null && !loopConfig.mHandlerName().isBlank()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).isContextMarker()) {
                    start = i + 1;
                    break;
                }
            }
        }
        for (int i = start; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message.isContextMarker()) {
                continue; // 压缩标记不进请求（理论上已被水位线裁掉，这里是显式兜底）
            }
            String text = message.text();
            if (text == null || text.isBlank()) {
                continue; // 停止后零增量的空占位卡等
            }
            if (Message.Role.ROLE_AI.equals(message.role())) {
                roleMessages.add(new PromptParams.RoleMessage(RoleEnum.ASSISTANT, text));
            } else if (Message.Role.ROLE_USER.equals(message.role())) {
                roleMessages.add(new PromptParams.RoleMessage(RoleEnum.USER, text));
            } else if (Message.Role.ROLE_TOOL.equals(message.role())) {
                roleMessages.add(new PromptParams.RoleMessage(RoleEnum.TOOL_RESULT, text,
                        message.toolArgs(), message.toolName(), message.callId()));
            }
        }
        return roleMessages;
    }

    /** 用户停止后把剩余增量抽干丢弃，后端读流线程才能走到 finally 放毒丸正常退出，不残留阻塞线程 */
    private static void drainUntilPoison(BlockingQueue<BaseEvent> events) {
        try {
            if (events == null) {
                return;
            }
            BaseEvent event;
            while (true) {
                event = events.poll(2, TimeUnit.SECONDS);
                if (event == null || event instanceof PoisonEvent) {
                    return; // 超时等不到毒丸就放弃（流可能已异常中断）
                }
            }
        } catch (InterruptedException ignored) {
        }
    }

    /** 链内单线程访问（loop 线程），无需原子类：requested 要跑的总轮数（首轮恒 1，续轮 +1），executed 已跑轮数 */
    private static class LoopControl {
        private int requested = 1;
        private int executed;

        void requestContinue() {
            requested++;
        }

        boolean hasMoreRounds() {
            return executed < requested;
        }

        int nextRound() {
            return ++executed;
        }
    }

    private static MessageView buildMessageView(String conId, Message message, MessageState messageState) {
        Role role = RecordConverter.toRole(message.role());
        if (role == null) {
            return null;
        }
        ToolView toolView = null;
        if (Role.TOOL_RESULT.equals(role)) {
            toolView = buildToolView(message);
        }
        return new MessageView(message.messageId(), null, role, message.text(), message.thinking(),
                message.time(), messageState, toolView);
    }

    private static ToolView buildToolView(Message message) {
        if (message == null || message.isContextMarker() || !message.isToolCall()) {
            return null;
        }
        return new ToolView(message.callId(), message.toolName(), message.toolArgs(),
                RecordConverter.toToolState(message.toolStatus()), false);
    }

    abstract class Flusher {

        private final Timer timer = new Timer(true);

        {
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    flush();
                }
            }, 500, 500);
        }

        abstract void flush();
    }

    class AiMessage extends Flusher {
        final String conId;
        Message message;
        final StringBuffer thinking = new StringBuffer();
        final StringBuffer content =  new StringBuffer();
        private boolean thinkDone;

        AiMessage(String conId) {
            this.conId = conId;
            eventPublisher.publish(seq -> {
                message = repo.appendMessage(conId, Message.Role.ROLE_AI, "");
                return new MessageAppended(conId,
                        buildMessageView(conId, message, MessageState.PENDING), seq);
            });
        }

        public void appendContent(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            thinkDone = true;
            content.append(delta);
            eventPublisher.publish(seq -> new MessageDelta(conId, message.messageId(), delta, null, seq));
        }

        public void appendThinking(String delta) {
            if (thinkDone || delta == null || delta.isEmpty()) {
                return;
            }
            thinking.append(delta);
            eventPublisher.publish(seq -> new MessageDelta(conId, message.messageId(), null, delta, seq));
        }

        public void finishThinking() {
            thinkDone = true;
        }

        public void finish() {
            super.timer.cancel();
            eventPublisher.publish(seq -> {
                repo.updateMessageContent(conId, message.messageId(), content.toString(), thinking.toString());
                return new MessageCompleted(conId, message.messageId(), seq);
            });
        }

        @Override
        void flush() {
            repo.updateMessageContent(conId, message.messageId(), content.toString(), thinking.toString());
        }
    }

    class ToolCallHandle {

        final String conId;
        Message message;

        final StringBuffer args = new StringBuffer();

        ToolCallHandle(String conversationId, String name, String callId) {
            this.conId = conversationId;
            eventPublisher.publish(seq -> {
                message = repo.appendToolCall(conversationId, name, "", callId);
                return new MessageAppended(conversationId, buildMessageView(conId, message, MessageState.PENDING), seq);
            });
        }

        public void appendArgs(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            args.append(delta);
            eventPublisher.publish(seq -> new ToolArgsDelta(conId, message.messageId(), message.callId(), delta, seq));
        }

        public void markExecuting() {
            eventPublisher.publish(seq -> new ToolStateChanged(conId, message.messageId(),
                    message.callId(), ToolState.RUNNING, "", "", false, seq));
        }

        public void finish(String result, boolean b, String arguments) {
            String finalArgs = (arguments == null || arguments.isEmpty()) ? args.toString() : arguments;
            eventPublisher.publish(seq -> {
                message = repo.completeToolCall(conId, message.messageId(), result, b, finalArgs);
                return new ToolStateChanged(conId, message.messageId(),
                        message.callId(), RecordConverter.toToolState(message.toolStatus()), result, finalArgs, false, seq);
            });
            eventPublisher.publish(seq -> new MessageCompleted(conId, message.messageId(), seq));
        }

        public void requestApproval(CompletableFuture<Boolean> decision) {
            long waitingMillis = 60_000L;
            String requestId = hostRequestRegistry.makeRequest(
                    answer -> processAnswer(decision, answer),
                    id -> getRequest(id, waitingMillis));
            CloseReason reason;
            try {
                decision.get(waitingMillis, TimeUnit.MILLISECONDS);
                reason = CloseReason.ANSWERED;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting for tool {} approval", message.toolName(), e);
                decision.cancel(true);
                reason = CloseReason.CANCELLED;
            } catch (ExecutionException e) {
                LOG.warn("Execution exception while waiting for tool {} approval", message.toolName(), e);
                reason = CloseReason.AUTO_REJECTED;
            } catch (TimeoutException e) {
                LOG.warn("Timeout while waiting for tool {} approval", message.toolName(), e);
                decision.cancel(true);
                reason = CloseReason.TIMEOUT;
            } catch (CancellationException e) {
                LOG.warn("Cancellation while waiting for tool {} approval", message.toolName(), e);
                reason = CloseReason.CANCELLED;
            }
            CloseReason finalReason = reason;
            eventPublisher.publish(seq -> new HostRequestClosed(requestId, conId, finalReason, seq));
        }

        private HostRequests.Request getRequest(String id, long waitingMillis) {
            long deadlineEpochMs = System.currentTimeMillis() + waitingMillis;
            HostRequests.ApprovalPayload payload = new HostRequests.ApprovalPayload(message.toolName(),
                    args.toString(), args.toString());
            HostRequests.Request request = new HostRequests.Request(id, conId, message.callId(),
                    HostRequests.Kind.TOOL_APPROVAL, payload, deadlineEpochMs);
            return request;
        }

        private void processAnswer(CompletableFuture<Boolean> decision, HostRequests.Answer answer) {
            if (answer == null) {
                decision.cancel(true);
                return;
            }
            if (answer instanceof HostRequests.Approve) {
                decision.complete(true);
            } else if (answer instanceof HostRequests.Reject reject) {
                decision.complete(false);
                LOG.info("tool approval was rejected for reason: {}", reject.reason());
            } else if (answer instanceof HostRequests.TextAnswer textAnswer) {
                decision.complete(false);
                LOG.info("tool approval was canceled for reason: {}", textAnswer.value());
            } else if (answer instanceof HostRequests.Cancel cancel) {
                decision.cancel(true);
                LOG.info("tool approval was canceled for reason: {}", cancel.reason());
            }
        }
    }
}

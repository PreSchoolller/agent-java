package io.github.preschoolller.agentjava.plugin;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawContentBlockStartEvent;
import com.anthropic.models.messages.RawContentBlockStopEvent;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStopEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.provider.common.AgentMode;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderBlockingTextResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderStreamingResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ReasoningEffortEnum;
import io.github.preschoolller.agentjava.backend.provider.common.RoleEnum;
import io.github.preschoolller.agentjava.backend.provider.common.ThreadPoolFactory;
import io.github.preschoolller.agentjava.backend.provider.common.ToolCall;
import io.github.preschoolller.agentjava.backend.provider.common.event.BaseEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ErrorMessageEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.FunctionToolCallDeltaEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.FunctionToolCallDoneEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.FunctionToolCallStartEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.PoisonEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ReasoningDeltaDoneEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ReasoningDeltaEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ReasoningDeltaStartEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseCompleteEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseDeltaDoneEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseDeltaEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseDeltaStartEvent;
import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import org.tinylog.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * anthropic /v1/messages api
 */
public class Messages implements Provider {
    private volatile MessageService service;
    static final Map<ReasoningEffortEnum, OutputConfig.Effort> RE_MAP = Map.of(
            ReasoningEffortEnum.NO, OutputConfig.Effort.LOW,
            ReasoningEffortEnum.LOW, OutputConfig.Effort.LOW,
            ReasoningEffortEnum.MEDIUM, OutputConfig.Effort.MEDIUM,
            ReasoningEffortEnum.HIGH, OutputConfig.Effort.HIGH,
            ReasoningEffortEnum.ULTRA, OutputConfig.Effort.XHIGH,
            ReasoningEffortEnum.ON, OutputConfig.Effort.HIGH,
            ReasoningEffortEnum.OFF, OutputConfig.Effort.LOW);
    private enum Type {
        REASONING,
        RESPONSE,
        FUNCTION
    }
    // TODO: AppSettings 耦合是不是可以去掉呢，直接由 PromptParams 传进来？？？
    private static final AppSettings appSettings = AppSettings.get();

    public Messages() {
        reload();
        AppSettings.get().addChangeListener(this::reload);
    }

    @Override
    public void reload() {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(appSettings.getKey())
                .baseUrl(appSettings.getBackendUrl())
                .build();

        service = client.messages();
    }

    @Override
    public String name() {
        return "messages-mine";
    }

    @Override
    public ProviderBlockingTextResponse sendPrompt(PromptParams promptParams) {
        MessageService messageService = service;
        promptParams = promptParams.toBuilder().agentMode(AgentMode.CHAT).build();
        MessageCreateParams createParams = buildParams(promptParams);
        Message message = messageService.create(createParams);

        StringBuilder reasoingContent = new StringBuilder();
        StringBuilder text = new StringBuilder();
        message.content().forEach(contentBlock -> {
            if (contentBlock.isText()) {
                text.append(contentBlock.text().orElseGet(() -> TextBlock.builder().text("").build()).text());
            } else if (contentBlock.isThinking()) {
                reasoingContent.append(contentBlock.thinking().orElseGet(() -> ThinkingBlock.builder().thinking("").build()).thinking());
            }
        });
        return new ProviderBlockingTextResponse(reasoingContent.toString(), text.toString());
    }

    MessageCreateParams buildParams(PromptParams promptParams) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder();

        for (PromptParams.RoleMessage roleMessage : promptParams.getRoleMessages()) {
            switch (roleMessage.role()) {
                case USER -> {
                    builder.addUserMessage(roleMessage.content());
                }
                case ASSISTANT -> {
                    builder.addAssistantMessage(roleMessage.content());
                }
                case TOOL_RESULT -> {
                    ToolUseBlockParam.Builder toolUseBlockParamBuilder = ToolUseBlockParam.builder();
                    toolUseBlockParamBuilder.id(roleMessage.callId());
                    toolUseBlockParamBuilder.name(roleMessage.toolName());
                    Map<String, JsonValue> inputMap = null;
                    try {
                        inputMap = Constants.getObjectMapper().readValue(
                                roleMessage.toolArguments(),
                                new TypeReference<>() {}
                        );
                    } catch (JsonProcessingException e) {
                        Logger.error(e, "parse tool arguments error, use null instead");
                    }

                    toolUseBlockParamBuilder.input(ToolUseBlockParam.Input.builder().additionalProperties(inputMap).build());
                    builder.addAssistantMessageOfContentBlockParams(
                            List.of(ContentBlockParam.ofToolUse(toolUseBlockParamBuilder.build())));

                    ToolResultBlockParam.Builder toolResultBlockParamBuilder = ToolResultBlockParam.builder();
                    toolResultBlockParamBuilder.toolUseId(roleMessage.callId());
                    toolResultBlockParamBuilder.content(roleMessage.content());
                    builder.addUserMessageOfContentBlockParams(
                            List.of(ContentBlockParam.ofToolResult(toolResultBlockParamBuilder.build())));
                }
                default -> {}
            }
        }

        if (!promptParams.getAgentMode().equals(AgentMode.CHAT)) {
            for (io.github.preschoolller.agentjava.backend.tool.interfaces.Tool tool : ToolManager.tools.values()) {
                Tool.Builder toolBuilder = Tool.builder();
                toolBuilder.name(tool.name());
                toolBuilder.description(tool.description());

                Map<String, JsonValue> parameters = new HashMap<>();
                if (tool.schema() != null) {
                    tool.schema().forEach((k, v) -> parameters.put(k, JsonValue.from(v)));
                }
                toolBuilder.inputSchema(Tool.InputSchema.builder()
                                .additionalProperties(parameters)
                        .build());
                builder.addTool(toolBuilder.build());
            }
        }

        builder.thinking(ThinkingConfigAdaptive.builder().build());
        builder.outputConfig(OutputConfig.builder()
                        .effort(RE_MAP.get(promptParams.getRe()))
                .build());

        return builder
                .model(promptParams.getModel())
                .maxTokens(8000)
                .build();
    }

    @Override
    public ProviderStreamingResponse sendPromptStreaming(PromptParams promptParams) {
        Logger.info("/v1/messages start streaming");
        MessageService messageService = service;
        MessageCreateParams createParams = buildParams(promptParams);
        BlockingQueue<BaseEvent> blockingQueue = new ArrayBlockingQueue<>(1024, true);
        AtomicBoolean shouldStop = new AtomicBoolean();
        ThreadPoolFactory.getResponseExecutor().execute(() -> {
            Map<Long, ToolCall> indexToolCall = new HashMap<>();
            Map<Long, Type> indexType = new HashMap<>();
            ErrorMessageEvent error = null;
            try (StreamResponse<RawMessageStreamEvent> streamEvents = messageService.createStreaming(createParams)) {
                streamEvents.stream()
                        .takeWhile(se -> !shouldStop.get())
                        .forEach(rawMessageStreamEvent -> visit(rawMessageStreamEvent, blockingQueue, indexType, indexToolCall));
            } catch (Throwable throwable) {
                // TODO 有哪些异常还不知道，先兜一下
                error = new ErrorMessageEvent(throwable.getMessage());
                Logger.error(throwable, "Unexpected error occurred");
            } finally {
                try {
                    if (error != null) {
                        blockingQueue.put(error);
                    }
                    blockingQueue.put(new PoisonEvent());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    blockingQueue.offer(new PoisonEvent());
                }
                Logger.info("/v1/messages stream end");
            }
        });
        return new ProviderStreamingResponse(blockingQueue, shouldStop);
    }

    private void visit(RawMessageStreamEvent rawMessageStreamEvent, BlockingQueue<BaseEvent> blockingQueue, Map<Long, Type> indexType, Map<Long, ToolCall> indexToolCall) {
        Logger.trace(rawMessageStreamEvent);
        if (rawMessageStreamEvent.isMessageStart()) {
            handleMessageStart(rawMessageStreamEvent.asMessageStart(), blockingQueue);
        } else if (rawMessageStreamEvent.isMessageDelta()) {
            handleMessageDelta(rawMessageStreamEvent.asMessageDelta(), blockingQueue);
        } else if (rawMessageStreamEvent.isMessageStop()) {
            handleMessageStop(rawMessageStreamEvent.asMessageStop(), blockingQueue);
        } else if (rawMessageStreamEvent.isContentBlockStart()) {
            handleContentBlockStart(rawMessageStreamEvent.asContentBlockStart(), blockingQueue, indexType, indexToolCall);
        } else if (rawMessageStreamEvent.isContentBlockDelta()) {
            handleContentBlockDelta(rawMessageStreamEvent.asContentBlockDelta(), blockingQueue, indexType, indexToolCall);
        } else if (rawMessageStreamEvent.isContentBlockStop()) {
            handleContentBlockStop(rawMessageStreamEvent.asContentBlockStop(), blockingQueue, indexType, indexToolCall);
        }
    }

    private void handleMessageStart(RawMessageStartEvent rawMessageStartEvent, BlockingQueue<BaseEvent> blockingQueue) {
        Logger.info("/v1/messages api stream started: {}", rawMessageStartEvent);
    }

    private void handleMessageDelta(RawMessageDeltaEvent rawMessageDeltaEvent, BlockingQueue<BaseEvent> blockingQueue) {
        Optional<StopReason> stopReason = rawMessageDeltaEvent.delta().stopReason();
        stopReason.ifPresent(reason -> Logger.info("/v1/messages api stream stopped for reason: {}", reason));
        MessageDeltaUsage usage = rawMessageDeltaEvent.usage();
        putIntoBQ(new ResponseCompleteEvent(usage.inputTokens().orElse(0L), usage.outputTokens()), blockingQueue);
    }

    private void handleMessageStop(RawMessageStopEvent rawMessageStopEvent, BlockingQueue<BaseEvent> blockingQueue) {
        putIntoBQ(new PoisonEvent(), blockingQueue);
    }

    private void handleContentBlockStart(RawContentBlockStartEvent rawContentBlockStartEvent, BlockingQueue<BaseEvent> blockingQueue, Map<Long, Type> indexType, Map<Long, ToolCall> indexToolCall) {
        RawContentBlockStartEvent.ContentBlock contentBlock = rawContentBlockStartEvent.contentBlock();
        long index = rawContentBlockStartEvent.index();
        if (contentBlock.isText()) {
            indexType.put(index, Type.RESPONSE);
            putIntoBQ(new ResponseDeltaStartEvent(String.valueOf(index)), blockingQueue);
        } else if (contentBlock.isThinking()) {
            indexType.put(index, Type.REASONING);
            putIntoBQ(new ReasoningDeltaStartEvent(String.valueOf(index)), blockingQueue);
        } else if (contentBlock.isToolUse()) {
            indexType.put(index, Type.FUNCTION);
            ToolUseBlock toolUse = contentBlock.asToolUse();
            putIntoBQ(new FunctionToolCallStartEvent(String.valueOf(index), toolUse.id(), toolUse.name()), blockingQueue);
            ToolCall toolCall = new ToolCall();
            toolCall.setTool(ToolManager.findByName(toolUse.name()));
            toolCall.setArguments("");
            toolCall.setCallId(toolUse.id());
            indexToolCall.put(index, toolCall);
        }
    }

    private void handleContentBlockDelta(RawContentBlockDeltaEvent rawContentBlockDeltaEvent, BlockingQueue<BaseEvent> blockingQueue, Map<Long, Type> indexType, Map<Long, ToolCall> indexToolCall) {
        RawContentBlockDelta delta = rawContentBlockDeltaEvent.delta();
        long index = rawContentBlockDeltaEvent.index();
        if (delta.isText()) {
            putIntoBQ(new ResponseDeltaEvent(String.valueOf(index), delta.asText().text()), blockingQueue);
        } else if (delta.isThinking()) {
            putIntoBQ(new ReasoningDeltaEvent(String.valueOf(index), delta.asThinking().thinking()), blockingQueue);
        } else if (delta.isInputJson()) {
            if (Type.FUNCTION.equals(indexType.get(index))) {
                ToolCall toolCall = indexToolCall.get(index);
                if (toolCall == null) {
                    Logger.error("unknown tool block delta event: {}", rawContentBlockDeltaEvent);
                    return;
                }
                String argusDelta = delta.asInputJson().partialJson();
                toolCall.setArguments(toolCall.getArguments() + argusDelta);
                putIntoBQ(new FunctionToolCallDeltaEvent(String.valueOf(index), argusDelta), blockingQueue);
            }
        }
    }

    private void handleContentBlockStop(RawContentBlockStopEvent rawContentBlockStopEvent, BlockingQueue<BaseEvent> blockingQueue, Map<Long, Type> indexType, Map<Long, ToolCall> indexToolCall) {
        // TODO: messages api 块结束不返回完整块内容，其实chat/completion也不返回，要说前端现在也没用上 ReasoningDeltaDoneEvent 的 text 字段，要不这个字段以后直接去掉得了？？？
        // 所以这一次就不再跟 chat/completion 一样自己维护一个StringBuilder来拼接思考块成品了。
        long index = rawContentBlockStopEvent.index();
        Type type = indexType.get(index);
        if (type == null) {
            return;
        }
        switch (type) {
            case REASONING -> putIntoBQ(new ReasoningDeltaDoneEvent(String.valueOf(index), ""), blockingQueue);
            case RESPONSE -> putIntoBQ(new ResponseDeltaDoneEvent(String.valueOf(index), ""), blockingQueue);
            case FUNCTION -> {
                ToolCall toolCall = indexToolCall.get(index);
                if (toolCall == null) {
                    Logger.error("unknown tool block stop event, index: {}", index);
                    return;
                }
                putIntoBQ(new FunctionToolCallDoneEvent(toolCall.getCallId(), toolCall), blockingQueue);
            }
            default -> Logger.error("unhandled type of content block stop event, index: {}", index);
        }
    }

    private static void putIntoBQ(BaseEvent baseEvent, BlockingQueue<BaseEvent> blockingQueue) {
        try {
            blockingQueue.put(baseEvent);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}

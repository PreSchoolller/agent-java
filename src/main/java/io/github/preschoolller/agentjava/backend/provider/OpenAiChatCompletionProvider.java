package io.github.preschoolller.agentjava.backend.provider;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.core.ClientOptions;
import com.openai.core.JsonValue;
import com.openai.core.http.Headers;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.completions.CompletionUsage;
import com.openai.services.blocking.chat.ChatCompletionService;
import io.github.preschoolller.agentjava.backend.provider.common.AgentMode;
import io.github.preschoolller.agentjava.backend.provider.common.BackGroundTask;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderBlockingTextResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderStreamingResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ReasoningEffortEnum;
import io.github.preschoolller.agentjava.backend.provider.common.ThreadPoolFactory;
import io.github.preschoolller.agentjava.backend.provider.common.ToolApprovalGate;
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
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseDeltaEvent;
import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import org.tinylog.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public class OpenAiChatCompletionProvider implements Provider {
    private volatile Headers headers;
    private volatile OkHttpClient httpClient;
    private volatile OpenAIClient openAIClient;
    private volatile ClientOptions clientOptions;
    private volatile ChatCompletionService chatCompletionService;
    static final Map<ReasoningEffortEnum, ReasoningEffort> RE_MAP = Map.of(
            ReasoningEffortEnum.NO, ReasoningEffort.NONE,
            ReasoningEffortEnum.LOW, ReasoningEffort.LOW,
            ReasoningEffortEnum.MEDIUM, ReasoningEffort.MEDIUM,
            ReasoningEffortEnum.HIGH, ReasoningEffort.HIGH,
            ReasoningEffortEnum.ULTRA, ReasoningEffort.XHIGH,
            ReasoningEffortEnum.ON, ReasoningEffort.HIGH,
            ReasoningEffortEnum.OFF, ReasoningEffort.NONE);

    private static final class InstanceHolder {
        private static final OpenAiChatCompletionProvider instance = new OpenAiChatCompletionProvider();
    }

    public static OpenAiChatCompletionProvider getInstance() {
        return InstanceHolder.instance;
    }

    private OpenAiChatCompletionProvider() {
        httpClient = OkHttpClient.builder().build();
        headers = Headers
                .builder()
                .build();
        clientOptions = ClientOptions
                .builder()
                .baseUrl(AppSettings.get().getBackendUrl())
                .apiKey(AppSettings.get().getKey())
                .headers(headers)
                .httpClient(httpClient)
                .build();
        openAIClient = new OpenAIClientImpl(clientOptions);
        chatCompletionService = openAIClient.chat().completions();
        AppSettings.get().addChangeListener(this::reload);
    }

    @Override
    public void reload() {
        synchronized (this) {
            clientOptions = clientOptions.toBuilder()
                    .baseUrl(AppSettings.get().getBackendUrl())
                    .apiKey(AppSettings.get().getKey())
                    .headers(headers)
                    .httpClient(httpClient)
                    .build();
            openAIClient = new OpenAIClientImpl(clientOptions);
            chatCompletionService = openAIClient.chat().completions();
        }
    }

    @Override
    public ProviderBlockingTextResponse sendPrompt(PromptParams promptParams) {
        ChatCompletionService service;
        synchronized (this) {
            service = chatCompletionService;
        }
        PromptParams chatParams = promptParams.toBuilder().agentMode(AgentMode.CHAT).build();
        ChatCompletionCreateParams chatCompletionCreateParams = buildParams(chatParams);
        ChatCompletion chatCompletion = service.create(chatCompletionCreateParams);

        return new ProviderBlockingTextResponse(getReasoningContent(chatCompletion), getMessageContent(chatCompletion));
    }

    private ChatCompletionCreateParams buildParams(PromptParams promptParams) {
        List<PromptParams.RoleMessage> roleMessages = promptParams.getRoleMessages();
        List<ChatCompletionMessageParam> allMessages = new ArrayList<>(roleMessages.size());
        for (PromptParams.RoleMessage roleMessage : roleMessages) {
            switch (roleMessage.role()) {
                case USER -> {
                    allMessages.add(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder()
                            .content(roleMessage.content())
                            .build()));
                }
                case ASSISTANT -> {
                    allMessages.add(ChatCompletionMessageParam.ofAssistant(ChatCompletionAssistantMessageParam.builder()
                            .content(roleMessage.content())
                            .build()));
                }
                case TOOL_RESULT -> {
                    if (promptParams.getAgentMode() == AgentMode.CHAT) {
                        continue; // 纯聊天请求不带工具，历史里的工具轮次一并跳过
                    }
                    allMessages.add(ChatCompletionMessageParam.ofAssistant(ChatCompletionAssistantMessageParam.builder()
                            .addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                                    .id(roleMessage.callId())
                                    .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                            .name(roleMessage.toolName())
                                            .arguments(roleMessage.toolArguments())
                                            .build())
                                    .build())
                            .build()));
                    allMessages.add(ChatCompletionMessageParam.ofTool(ChatCompletionToolMessageParam.builder()
                            .toolCallId(roleMessage.callId())
                            .content(roleMessage.content())
                            .build()));
                }
                default -> throw new RuntimeException("unknown role enum");
            }
        }
        ArrayList<ChatCompletionMessageParam> inputList = new ArrayList<>();
        inputList.add(systemMessage(promptParams));
        inputList.addAll(allMessages);
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder();
        builder.model(promptParams.getModel())
                .reasoningEffort(RE_MAP.get(promptParams.getRe()))
                .streamOptions(ChatCompletionStreamOptions.builder()
                        .includeUsage(true)
                        .build())
                .parallelToolCalls(true)
                .messages(inputList);
        if (promptParams.getAgentMode() == AgentMode.CHAT) {
            return builder.build(); // 纯聊天：不声明任何工具，模型调不了东西，审批/执行链路整个不参与
        }
        for (Tool tool : ToolManager.tools.values()) {
            builder.addTool(ToolManager.getChatCompletionFunctionTool(tool));
        }
        return builder.build();
    }

    private static ChatCompletionMessageParam systemMessage(PromptParams promptParams) {
        String text = AppSettings.get().getSystemPrompt();
        String workspace = promptParams.getWorkspace();
        if (promptParams.getAgentMode() != AgentMode.CHAT && workspace != null && !workspace.isBlank()) {
            text = text + "\n\n当前工作区目录：" + workspace + "。工具调用中的相对路径以此为基准。";
        }
        return ChatCompletionMessageParam.ofSystem(ChatCompletionSystemMessageParam.builder()
                .content(text)
                .build());
    }

    @Override
    public ProviderStreamingResponse sendPromptStreaming(PromptParams promptParams) {
        Logger.info("--------------streaming start--------------");
        ChatCompletionService service;
        synchronized (this) {
            service = chatCompletionService;
        }
        ChatCompletionCreateParams chatCompletionCreateParams = buildParams(promptParams);
        BlockingQueue<BaseEvent> blockingQueue = new ArrayBlockingQueue<>(1024, true);
        Map<Integer, ToolCall> indexToolCall = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        AtomicBoolean shouldStop = new AtomicBoolean(false);
        ThreadPoolFactory.getResponseExecutor().execute(() -> {
            List<ErrorMessageEvent> errors = new ArrayList<>();
            try (StreamResponse<ChatCompletionChunk> response = service.createStreaming(chatCompletionCreateParams)) {
                response.stream()
                        .takeWhile(chatCompletionChunk -> !shouldStop.get())
                        .forEach(chatCompletionChunk ->
                                visit(blockingQueue, promptParams.getApprovalGate(),
                                        promptParams.getBackGroundTask(), promptParams.getWorkspace(),
                                        chatCompletionChunk, indexToolCall, sb));
            } catch (OpenAIServiceException exception) {
                String errorMessage = "Stream exception found. Code: " + exception.code().orElse("")
                        + "\nStatus code: " + exception.statusCode()
                        + "\nDetail message: " + exception.getMessage();
                errors.add(new ErrorMessageEvent(errorMessage));
            } catch (OpenAIIoException exception) {
                errors.add(new ErrorMessageEvent("Stream event iteration failed: " + exception.getMessage()));
            } catch (RuntimeException exception) {
                exception.printStackTrace();
                errors.add(new ErrorMessageEvent("Unexpected stream error: " + exception.getMessage()));
            } finally {
                Logger.info("--------------streaming end--------------");
                putIntoBQ(new ReasoningDeltaDoneEvent("", sb.toString().trim().strip()), blockingQueue);
                try {
                    for (ErrorMessageEvent error : errors) {
                        blockingQueue.put(error);
                    }
                    blockingQueue.put(new PoisonEvent());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    blockingQueue.offer(new PoisonEvent());
                }
            }
        });

        return new ProviderStreamingResponse(blockingQueue, shouldStop);
    }

    public static String getReasoningContent(ChatCompletion chatCompletion) {
        // TODO
        return "";
    }

    public static String getMessageContent(ChatCompletion response) {
        // TODO
        return "";
    }

    private static void visit(BlockingQueue<BaseEvent> blockingQueue,
                              ToolApprovalGate approvalGate, BackGroundTask backGroundTask, String workspace,
                              ChatCompletionChunk chatCompletionChunk, Map<Integer, ToolCall> indexToolCall, StringBuilder sb) {
        Logger.trace(chatCompletionChunk);
        Stream<ChatCompletionChunk.Choice.Delta> deltaStream = chatCompletionChunk.choices().stream()
                .map(ChatCompletionChunk.Choice::delta);
        deltaStream.forEach(delta -> {
            if (delta.content().isPresent() && !delta.content().get().isEmpty()) {
                putIntoBQ(new ResponseDeltaEvent(chatCompletionChunk.id(), delta.content().get()), blockingQueue);
            }
            JsonValue reasoningContent = delta._additionalProperties().get("reasoning_content");
            JsonValue reasoning = delta._additionalProperties().get("reasoning");
            if (reasoningContent != null || reasoning != null) {
                String reasoningDelta = "";
                if (reasoningContent != null) {
                    reasoningDelta += (String) reasoningContent.asString().orElse("");
                }
                if (reasoning != null) {
                    reasoningDelta += (String) reasoning.asString().orElse("");
                }
                if (sb.isEmpty() && !reasoningDelta.isEmpty()) {
                    putIntoBQ(new ReasoningDeltaStartEvent(""), blockingQueue);
                }
                sb.append(reasoningDelta);
                putIntoBQ(new ReasoningDeltaEvent(chatCompletionChunk.id(), reasoningDelta), blockingQueue);
            }
            if (delta.toolCalls().isPresent()) {
                for (ChatCompletionChunk.Choice.Delta.ToolCall deltaToolCall : delta.toolCalls().get()) {
                    int index = (int) deltaToolCall.index();
                    Optional<String> callId = deltaToolCall.id();
                    Optional<ChatCompletionChunk.Choice.Delta.ToolCall.Function> opFunction = deltaToolCall.function();
                    if (opFunction.isPresent()) {
                        ChatCompletionChunk.Choice.Delta.ToolCall.Function function = opFunction.get();
                        String name = function.name().orElse("");
                        Optional<String> arguments = function.arguments();
                        if (callId.isPresent()) {
                            putIntoBQ(new FunctionToolCallStartEvent(String.valueOf(index), callId.get(), name), blockingQueue);
                            ToolCall toolCall = new ToolCall();
                            toolCall.setCallId(callId.get());
                            toolCall.setTool(ToolManager.findByName(name));
                            toolCall.setArguments("");
                            toolCall.setStatus(ToolCall.Status.IN_PROGRESS);
                            indexToolCall.put(index, toolCall);
                        }
                        ToolCall toolCall = indexToolCall.get(index);
                        if (toolCall == null) {
                            System.out.println("哦吼，这里还能为null，弃了");
                        } else {
                            toolCall.setArguments(toolCall.getArguments() + arguments.orElse(""));
                        }
                        putIntoBQ(new FunctionToolCallDeltaEvent(String.valueOf(index), arguments.orElse("")), blockingQueue);
                    }
                }
            }
        });

        // TODO usage和finish_reason不一定哪个先到，所以暂不投毒，依赖finally
        Optional<CompletionUsage> usage = chatCompletionChunk.usage();
        if (usage.isPresent()) {
            putIntoBQ(new ResponseCompleteEvent(usage.get().promptTokens(), usage.get().completionTokens()), blockingQueue);
        }


        Optional<ChatCompletionChunk.Choice.FinishReason> finishReason = chatCompletionChunk.choices().stream()
                .map(ChatCompletionChunk.Choice::finishReason)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findAny();
        if (finishReason.isPresent()) {
            Logger.info("流式传输终止，原因：{}", finishReason.get());
            for (Integer index : indexToolCall.keySet()) {
                ToolCall toolCall = indexToolCall.get(index);
                toolCall.setApprovalGate(approvalGate);
                toolCall.setWorkspace(workspace);
                toolCall.setBackGroundTask(backGroundTask);
                putIntoBQ(new FunctionToolCallDoneEvent(toolCall.getCallId(), toolCall), blockingQueue);
                ThreadPoolFactory.getToolCallExecutor().execute(toolCall);
            }
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

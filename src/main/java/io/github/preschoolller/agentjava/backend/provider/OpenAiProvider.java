package io.github.preschoolller.agentjava.backend.provider;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.core.ClientOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseContentPartAddedEvent;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseFunctionCallArgumentsDeltaEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseIncompleteEvent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputItemAddedEvent;
import com.openai.models.responses.ResponseOutputItemDoneEvent;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseReasoningTextDeltaEvent;
import com.openai.models.responses.ResponseReasoningTextDoneEvent;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.services.blocking.ResponseService;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderBlockingTextResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderStreamingResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ReasoningEffortEnum;
import io.github.preschoolller.agentjava.backend.provider.common.ResponseStreamEventVisitorAdaptor;
import io.github.preschoolller.agentjava.backend.provider.common.ThreadPoolFactory;
import io.github.preschoolller.agentjava.backend.provider.common.ToolCall;
import io.github.preschoolller.agentjava.backend.provider.common.event.BaseEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.FunctionToolCallDeltaEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.FunctionToolCallDoneEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.FunctionToolCallStartEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.PoisonEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ReasoningDeltaDoneEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ReasoningDeltaEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ReasoningDeltaStartEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseCompleteEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseDeltaEvent;
import io.github.preschoolller.agentjava.backend.provider.common.event.ResponseDeltaStartEvent;
import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

public class OpenAiProvider implements Provider {
    private volatile Headers headers;
    private volatile OkHttpClient httpClient;
    private volatile OpenAIClient openAIClient;
    private volatile ClientOptions clientOptions;
    private volatile ResponseService responseService;
    static final Map<ReasoningEffortEnum, ReasoningEffort> RE_MAP = Map.of(
            ReasoningEffortEnum.NO, ReasoningEffort.NONE,
            ReasoningEffortEnum.LOW, ReasoningEffort.LOW,
            ReasoningEffortEnum.MEDIUM, ReasoningEffort.MEDIUM,
            ReasoningEffortEnum.HIGH, ReasoningEffort.HIGH,
            ReasoningEffortEnum.ULTRA, ReasoningEffort.XHIGH,
            ReasoningEffortEnum.ON, ReasoningEffort.HIGH,
            ReasoningEffortEnum.OFF, ReasoningEffort.NONE);

    private volatile ResponseInputItem.Message systemPromptMessage;

    OpenAiProvider() {
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
        responseService = openAIClient.responses();
        ResponseInputText systemPrompt = ResponseInputText
                .builder()
                .text(AppSettings.get().getSystemPrompt())
                .build();
        systemPromptMessage = ResponseInputItem.Message
                .builder()
                .role(ResponseInputItem.Message.Role.SYSTEM)
                .addContent(systemPrompt).build();
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
            responseService = openAIClient.responses();
            ResponseInputText systemPrompt = ResponseInputText
                    .builder()
                    .text(AppSettings.get().getSystemPrompt())
                    .build();
            systemPromptMessage = ResponseInputItem.Message
                    .builder()
                    .role(ResponseInputItem.Message.Role.SYSTEM)
                    .addContent(systemPrompt).build();
        }
    }

    @Override
    public ProviderBlockingTextResponse sendPrompt(PromptParams promptParams) {
        ResponseService service;
        synchronized (this) {
            service = responseService;
        }
        PromptParams justReadParams = promptParams.toBuilder().justChat(true).build();
        ResponseCreateParams responseCreateParams = buildParams(justReadParams);
        Response response = service.create(responseCreateParams);

        return new ProviderBlockingTextResponse(getReasoningContent(response), getMessageContent(response));
    }

    private ResponseCreateParams buildParams(PromptParams promptParams) {
        List<PromptParams.RoleMessage> roleMessages = promptParams.getRoleMessages();
        List<ResponseInputItem> allMessages = new ArrayList<>(roleMessages.size());
        for (PromptParams.RoleMessage roleMessage : roleMessages) {
            switch (roleMessage.role()) {
                case USER -> {
                    ResponseInputText inputText = ResponseInputText
                            .builder()
                            .text(roleMessage.content())
                            .build();
                    ResponseInputItem.Message message = ResponseInputItem.Message
                            .builder()
                            .role(ResponseInputItem.Message.Role.USER)
                            .addContent(inputText)
                            .build();
                    allMessages.add(ResponseInputItem.ofMessage(message));
                }
                case ASSISTANT -> {
                    EasyInputMessage easyInputMessage = EasyInputMessage
                            .builder()
                            .role(EasyInputMessage.Role.ASSISTANT)
                            .content(roleMessage.content())
                            .build();
                    allMessages.add(ResponseInputItem.ofEasyInputMessage(easyInputMessage));
                }
                case TOOL_RESULT -> {
                    if (promptParams.getJustChat()) {
                        continue;
                    }
                    ResponseFunctionToolCall responseFunctionToolCall = ResponseFunctionToolCall
                            .builder()
                            .callId(roleMessage.callId())
                            .arguments(roleMessage.toolArguments())
                            .name(roleMessage.toolName())
                            .build();
                    allMessages.add(ResponseInputItem.ofFunctionCall(responseFunctionToolCall));
                    ResponseInputItem.FunctionCallOutput functionCallOutput = ResponseInputItem.FunctionCallOutput
                            .builder()
                            .callId(roleMessage.callId())
                            .output(roleMessage.content())
                            .build();
                    allMessages.add(ResponseInputItem.ofFunctionCallOutput(functionCallOutput));
                }
                default -> throw new RuntimeException("unknown role enum");
            }
        }
        ArrayList<ResponseInputItem> inputList = new ArrayList<>();
        inputList.add(ResponseInputItem.ofMessage(systemPromptMessage));
        inputList.addAll(allMessages);
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder();
        builder.model(promptParams.getModel())
                .reasoning(Reasoning.builder().effort(RE_MAP.get(promptParams.getRe())).build())
                .inputOfResponse(inputList);
        if (promptParams.getJustChat()) {
            return builder.build();
        }
        for (Tool tool : ToolManager.tools) {
            builder.addTool(ToolManager.getFunctionTool(tool));
        }
        return builder.build();
    }

    @Override
    public ProviderStreamingResponse sendPromptStreaming(PromptParams promptParams) {
        System.out.println("有人调用？？？------------------");
        ResponseService service;
        synchronized (this) {
            service = responseService;
        }
        ResponseCreateParams responseCreateParams = buildParams(promptParams);
        BlockingQueue<BaseEvent> blockingQueue = new ArrayBlockingQueue<>(1024, true);
        StreamResponse<ResponseStreamEvent> response = service.createStreaming(responseCreateParams);
        ResponseStreamEventVisitorAdaptor visitor = getVisitor(blockingQueue);
        new Thread(() -> {
            try {
                response.stream()
                        .forEach(responseStreamEvent -> responseStreamEvent.accept(visitor));
            } finally {
                blockingQueue.offer(new PoisonEvent());
            }
        }).start();

        return new ProviderStreamingResponse(blockingQueue);
    }

    public static String getReasoningContent(Response response) {
        Optional<ResponseOutputItem> reasoningOptional = response
                .output()
                .stream()
                .filter(ResponseOutputItem::isReasoning)
                .findFirst();
        if (reasoningOptional.isPresent()) {
            Optional<List<ResponseReasoningItem.Content>> contentOptional = reasoningOptional.get().asReasoning().content();
            if (contentOptional.isPresent()) {
                List<ResponseReasoningItem.Content> contents = contentOptional.get();
                if (!contents.isEmpty()) {
                    return contents.stream().map(ResponseReasoningItem.Content::text).collect(Collectors.joining("\n"));
                }
            }
        }
        return "";
    }

    public static String getMessageContent(Response response) {
        Optional<ResponseOutputItem> messageOptional = response
                .output()
                .stream()
                .filter(ResponseOutputItem::isMessage)
                .findFirst();
        if (messageOptional.isPresent()) {
            List<ResponseOutputMessage.Content> contents = messageOptional.get().asMessage().content();
            if (!contents.isEmpty()) {
                return contents.stream()
                        .map(ResponseOutputMessage.Content::outputText)
                        .filter(Optional::isPresent)
                        .map(unit -> unit.get().text())
                        .collect(Collectors.joining("\n"));
            }
        }
        return "";
    }

    private static ResponseStreamEventVisitorAdaptor getVisitor(BlockingQueue<BaseEvent> blockingQueue) {
        return new ResponseStreamEventVisitorAdaptor() {

            private void putIntoBQ(BaseEvent baseEvent) {
                try {
                    blockingQueue.put(baseEvent);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public Object visitContentPartAdded(@NotNull ResponseContentPartAddedEvent responseContentPartAddedEvent) {
                if (responseContentPartAddedEvent.part().isReasoningText()) {
                    putIntoBQ(new ReasoningDeltaStartEvent(responseContentPartAddedEvent.itemId()));
                }
                if (responseContentPartAddedEvent.part().isOutputText()) {
                    putIntoBQ(new ResponseDeltaStartEvent(responseContentPartAddedEvent.itemId()));
                }
                return null;
            }

            @Override
            public Object visitReasoningTextDelta(@NotNull ResponseReasoningTextDeltaEvent responseReasoningTextDeltaEvent) {
                putIntoBQ(new ReasoningDeltaEvent(responseReasoningTextDeltaEvent.itemId(), responseReasoningTextDeltaEvent.delta()));
                return null;
            }

            @Override
            public Object visitReasoningTextDone(@NotNull ResponseReasoningTextDoneEvent responseReasoningTextDoneEvent) {
                putIntoBQ(new ReasoningDeltaDoneEvent(responseReasoningTextDoneEvent.itemId(), responseReasoningTextDoneEvent.text()));
                return null;
            }

            @Override
            public Object visitOutputTextDelta(@NotNull ResponseTextDeltaEvent responseTextDeltaEvent) {
                putIntoBQ(new ResponseDeltaEvent(responseTextDeltaEvent.itemId(), responseTextDeltaEvent.delta()));
                return null;
            }

            @Override
            public Object visitCompleted(@NotNull ResponseCompletedEvent responseCompletedEvent) {
                putIntoBQ(new ResponseCompleteEvent());
                putIntoBQ(new PoisonEvent());
                return null;
            }

            @Override
            public Object visitFunctionCallArgumentsDelta(@NotNull ResponseFunctionCallArgumentsDeltaEvent responseFunctionCallArgumentsDeltaEvent) {
                putIntoBQ(new FunctionToolCallDeltaEvent(responseFunctionCallArgumentsDeltaEvent.itemId(), responseFunctionCallArgumentsDeltaEvent.delta()));
                return null;
            }

            @Override
            public Object visitOutputItemAdded(@NotNull ResponseOutputItemAddedEvent responseOutputItemAddedEvent) {
                if (responseOutputItemAddedEvent.item().isFunctionCall()) {
                    ResponseFunctionToolCall functionCall = responseOutputItemAddedEvent.item().asFunctionCall();
                    putIntoBQ(new FunctionToolCallStartEvent(functionCall.id().orElse("wrong_id"),
                            functionCall.callId(),
                            functionCall.name()));
                }
                return null;
            }

            @Override
            public Object visitFailed(@NotNull ResponseFailedEvent responseFailedEvent) {
                putIntoBQ(new PoisonEvent());
                return null;
            }

            @Override
            public Object visitIncomplete(@NotNull ResponseIncompleteEvent responseIncompleteEvent) {
                putIntoBQ(new PoisonEvent());
                return null;
            }

            @Override
            public Object visitOutputItemDone(@NotNull ResponseOutputItemDoneEvent responseOutputItemDoneEvent) {
                if (responseOutputItemDoneEvent.item().isFunctionCall()) {
                    ResponseFunctionToolCall functionCall = responseOutputItemDoneEvent.item().asFunctionCall();
                    ToolCall toolCall = new ToolCall();
                    toolCall.setArguments(functionCall.arguments());
                    toolCall.setTool(ToolManager.findByName(functionCall.name()).orElse(ToolManager.BAD_TOOL));
                    toolCall.setCallId(functionCall.callId());
                    putIntoBQ(new FunctionToolCallDoneEvent(functionCall.callId(), toolCall));
                    ThreadPoolFactory.getExecutor().execute(() -> {
                        try {
                            toolCall.setResult(ToolManager.execute(functionCall));
                            toolCall.setStatus(ToolCall.Status.COMPLETE);
                        } catch (Exception e) {
                            toolCall.setResult("tool call error, id: " + toolCall.getCallId() + " with message: " + e.getMessage());
                            toolCall.setStatus(ToolCall.Status.INCOMPLETE);
                        }
                    });
                }
                return null;
            }
        };
    }
}

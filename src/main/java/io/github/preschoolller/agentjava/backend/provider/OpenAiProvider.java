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
import com.openai.models.responses.ResponseFunctionToolCall;
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
import io.github.preschoolller.agentjava.backend.provider.common.ToolCall;
import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import static io.github.preschoolller.agentjava.backend.provider.common.Constants.CALL_ID_SEPERATOR;
import static io.github.preschoolller.agentjava.backend.provider.common.Constants.POISON;

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
        ResponseCreateParams responseCreateParams = buildParams(promptParams);
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
                    String content = roleMessage.content();
                    String[] split = content.split(CALL_ID_SEPERATOR);
                    ResponseFunctionToolCall responseFunctionToolCall = ResponseFunctionToolCall
                            .builder()
                            .callId(split[0])
                            .arguments(roleMessage.toolArguments())
                            .name(roleMessage.toolName())
                            .build();
                    allMessages.add(ResponseInputItem.ofFunctionCall(responseFunctionToolCall));
                    ResponseInputItem.FunctionCallOutput functionCallOutput = ResponseInputItem.FunctionCallOutput
                            .builder()
                            .callId(split[0])
                            .output(split[split.length - 1])
                            .build();
                    allMessages.add(ResponseInputItem.ofFunctionCallOutput(functionCallOutput));
                }
            }
        }
        ArrayList<ResponseInputItem> inputList = new ArrayList<>();
        inputList.add(ResponseInputItem.ofMessage(systemPromptMessage));
        inputList.addAll(allMessages);
        ResponseCreateParams responseCreateParams = ResponseCreateParams
                .builder()
                .model(promptParams.getModel())
                .reasoning(Reasoning.builder().effort(RE_MAP.get(promptParams.getRe())).build())
                .inputOfResponse(inputList)
                .addTool(Objects.requireNonNull(ToolManager.getFunctionTool("read")))
                .build();
        return responseCreateParams;
    }

    @Override
    public ProviderStreamingResponse sendPromptStreaming(PromptParams promptParams) {
        ResponseService service;
        synchronized (this) {
            service = responseService;
        }
        ResponseCreateParams responseCreateParams = buildParams(promptParams);
        BlockingQueue<String> blockingQueue = new ArrayBlockingQueue<>(256, true);
        ToolCall toolCall = new ToolCall();
        StreamResponse<ResponseStreamEvent> response = service.createStreaming(responseCreateParams);
        ResponseStreamEventVisitorAdaptor visitor = getVisitor(blockingQueue, toolCall);
        new Thread(() -> {
            try {
                response.stream()
                        .forEach(responseStreamEvent -> responseStreamEvent.accept(visitor));
            } finally {
                blockingQueue.offer(POISON);
            }
        }).start();

        return new ProviderStreamingResponse(blockingQueue, toolCall);
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

    private static ResponseStreamEventVisitorAdaptor getVisitor(BlockingQueue<String> blockingQueue, ToolCall toolCall) {
        return new ResponseStreamEventVisitorAdaptor() {

            private void putIntoBQ(String delta) {
                try {
                    blockingQueue.put(delta);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public Object visitContentPartAdded(@NotNull ResponseContentPartAddedEvent responseContentPartAddedEvent) {
                if (responseContentPartAddedEvent.part().isReasoningText()) {
                    putIntoBQ("<think>");
                }
                return null;
            }

            @Override
            public Object visitReasoningTextDelta(ResponseReasoningTextDeltaEvent responseReasoningTextDeltaEvent) {
                putIntoBQ(responseReasoningTextDeltaEvent.delta());
                return null;
            }

            @Override
            public Object visitReasoningTextDone(@NotNull ResponseReasoningTextDoneEvent responseReasoningTextDoneEvent) {
                putIntoBQ("</think>");
                return null;
            }

            @Override
            public Object visitOutputTextDelta(ResponseTextDeltaEvent responseTextDeltaEvent) {
                putIntoBQ(responseTextDeltaEvent.delta());
                return null;
            }

            @Override
            public Object visitCompleted(ResponseCompletedEvent responseCompletedEvent) {
                putIntoBQ(POISON);
                return null;
            }

            @Override
            public Object visitOutputItemAdded(@NotNull ResponseOutputItemAddedEvent responseOutputItemAddedEvent) {
                if (responseOutputItemAddedEvent.item().isFunctionCall()) {
                    System.out.println("工具调用开始传输");
                    ResponseFunctionToolCall functionCall = responseOutputItemAddedEvent.item().asFunctionCall();
                    toolCall.setTool(ToolManager.findByName(functionCall.name()).orElse(ToolManager.BAD_TOOL));
                    toolCall.setStatus(ToolCall.Status.IN_PROGRESS);
                    toolCall.setCallId(functionCall.callId());
                }
                return null;
            }

            @Override
            public Object visitOutputItemDone(@NotNull ResponseOutputItemDoneEvent responseOutputItemDoneEvent) {
                if (responseOutputItemDoneEvent.item().isFunctionCall()) {
                    System.out.println("工具调用传输结束");
                    ResponseFunctionToolCall functionCall = responseOutputItemDoneEvent.item().asFunctionCall();
                    if (functionCall.status().isPresent() && functionCall.status().get().equals(ResponseFunctionToolCall.Status.COMPLETED)) {
                        System.out.println("工具调用传输完成");
                    }
                    System.out.println("**********************执行工具参数**********************");
                    System.out.println(functionCall);
                    System.out.println("**********************执行工具开始**********************");
                    String result = ToolManager.execute(functionCall);
                    System.out.println(result);
                    toolCall.setStatus(ToolCall.Status.COMPLETE);
                    toolCall.setArguments(functionCall.arguments());
                    toolCall.setResult(result);
                    System.out.println("**********************执行工具结果**********************");
                }
                return null;
            }
        };
    }
}

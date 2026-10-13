package io.github.preschoolller.agentjava.backend.provider;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.core.ClientOptions;
import com.openai.core.http.Headers;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseContentPartAddedEvent;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseErrorEvent;
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
import io.github.preschoolller.agentjava.backend.provider.common.AgentMode;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderBlockingTextResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderStreamingResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ReasoningEffortEnum;
import io.github.preschoolller.agentjava.backend.provider.common.ResponseStreamEventVisitorAdaptor;
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
        }
    }

    @Override
    public ProviderBlockingTextResponse sendPrompt(PromptParams promptParams) {
        ResponseService service;
        synchronized (this) {
            service = responseService;
        }
        PromptParams chatParams = promptParams.toBuilder().agentMode(AgentMode.CHAT).build();
        ResponseCreateParams responseCreateParams = buildParams(chatParams);
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
                    if (promptParams.getAgentMode() == AgentMode.CHAT) {
                        continue; // 纯聊天请求不带工具，历史里的工具轮次一并跳过
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
        inputList.add(ResponseInputItem.ofMessage(systemMessage(promptParams)));
        inputList.addAll(allMessages);
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder();
        builder.model(promptParams.getModel())
                .reasoning(Reasoning.builder().effort(RE_MAP.get(promptParams.getRe())).build())
                .inputOfResponse(inputList);
        if (promptParams.getAgentMode() == AgentMode.CHAT) {
            return builder.build(); // 纯聊天：不声明任何工具，模型调不了东西，审批/执行链路整个不参与
        }
        for (Tool tool : ToolManager.tools) {
            builder.addTool(ToolManager.getFunctionTool(tool));
        }
        return builder.build();
    }

    /**
     * 系统提示词按请求组装（原来构造期缓存成字段——工作区要每请求注入，缓存没了意义）：
     * 设置里的文本打底；涉工具的请求（非纯聊天）再补一行工作区说明，告知模型相对路径的
     * 基准——工具侧（Tool#execute）按同一目录解析，两边必须说同一个词。
     */
    private static ResponseInputItem.Message systemMessage(PromptParams promptParams) {
        String text = AppSettings.get().getSystemPrompt();
        String workspace = promptParams.getWorkspace();
        if (promptParams.getAgentMode() != AgentMode.CHAT && workspace != null && !workspace.isBlank()) {
            text = text + "\n\n当前工作区目录：" + workspace + "。工具调用中的相对路径以此为基准。";
        }
        ResponseInputText inputText = ResponseInputText
                .builder()
                .text(text)
                .build();
        return ResponseInputItem.Message
                .builder()
                .role(ResponseInputItem.Message.Role.SYSTEM)
                .addContent(inputText).build();
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
        ResponseStreamEventVisitorAdaptor visitor = getVisitor(blockingQueue,
                promptParams.getApprovalGate(), promptParams.getAgentMode(), promptParams.getWorkspace());
        ThreadPoolFactory.getResponseExecutor().execute(() -> {
            List<ErrorMessageEvent> errors = new ArrayList<>();
            try (StreamResponse<ResponseStreamEvent> response = service.createStreaming(responseCreateParams)) {
                response.stream()
                        .forEach(responseStreamEvent -> responseStreamEvent.accept(visitor));
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

    private static ResponseStreamEventVisitorAdaptor getVisitor(BlockingQueue<BaseEvent> blockingQueue,
                                                                 ToolApprovalGate approvalGate,
                                                                 AgentMode agentMode,
                                                                 String workspace) {
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
                // token 用量随 completed 事件的 response.usage 一起来，捎给前端胶囊展示
                long inputTokens = responseCompletedEvent.response().usage()
                        .map(com.openai.models.responses.ResponseUsage::inputTokens).orElse(0L);
                long outputTokens = responseCompletedEvent.response().usage()
                        .map(com.openai.models.responses.ResponseUsage::outputTokens).orElse(0L);
                putIntoBQ(new ResponseCompleteEvent(inputTokens, outputTokens));
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
                responseFailedEvent.response().error()
                        .ifPresent(responseError -> putIntoBQ(
                                new ErrorMessageEvent("Stream failed. Code: " + responseError.code() + "\nMessage: " + responseError.message())));
                putIntoBQ(new PoisonEvent());
                return null;
            }

            @Override
            public Object visitIncomplete(@NotNull ResponseIncompleteEvent responseIncompleteEvent) {
                responseIncompleteEvent.response().incompleteDetails()
                        .flatMap(Response.IncompleteDetails::reason)
                        .ifPresent(reason -> putIntoBQ(new ErrorMessageEvent("Stream incomplete. Reason: " + reason)));
                putIntoBQ(new PoisonEvent());
                return null;
            }

            @Override
            public Object visitError(@NotNull ResponseErrorEvent responseErrorEvent) {
                putIntoBQ(new ErrorMessageEvent("Stream encountered error. Code: " + responseErrorEvent.code().orElse("")
                        + "\n message: " + responseErrorEvent.message()));
                putIntoBQ(new PoisonEvent());
                return null;
            }

            @Override
            public Object visitOutputItemDone(@NotNull ResponseOutputItemDoneEvent responseOutputItemDoneEvent) {
                if (responseOutputItemDoneEvent.item().isFunctionCall()) {
                    ResponseFunctionToolCall functionCall = responseOutputItemDoneEvent.item().asFunctionCall();
                    ToolCall toolCall = new ToolCall();
                    toolCall.setArguments(functionCall.arguments());
                    toolCall.setTool(ToolManager.findByName(functionCall.name()));
                    toolCall.setCallId(functionCall.callId());
                    putIntoBQ(new FunctionToolCallDoneEvent(functionCall.callId(), toolCall));
                    ThreadPoolFactory.getToolCallExecutor().execute(() -> {
                        // 审批在池线程上做（可阻塞等用户点击）；读流线程只管转发事件，绝不停顿
                        if (approvalGate != null && !approvalGate.allow(toolCall, agentMode)) {
                            toolCall.markExecutionStarted(); // 先解锁等待方，紧随其后的终态让它直接返回
                            toolCall.setResult(ToolApprovalGate.DENIED_RESULT);
                            toolCall.setStatus(ToolCall.Status.INCOMPLETE);
                            return;
                        }
                        toolCall.markExecutionStarted(); // 进入执行阶段，awaitFinal 的执行超时从这里起算（审批等待不计入）
                        try {
                            toolCall.setResult(ToolManager.execute(functionCall, workspace));
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

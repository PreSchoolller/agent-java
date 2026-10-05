package io.github.preschoolller.agentjava.backend.provider;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.core.ClientOptions;
import com.openai.core.http.Headers;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.services.blocking.ResponseService;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ReasoningEffortEnum;
import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.store.AppSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class OpenAiProvider implements Provider {
    private volatile Headers headers;
    private volatile OkHttpClient httpClient;
    private volatile OpenAIClient openAIClient;
    private volatile ClientOptions clientOptions;
    private volatile ResponseService responseService;
    static final Map<ReasoningEffortEnum, ReasoningEffort> RE_MAP = new HashMap<>() {
        {
            put(ReasoningEffortEnum.NO, ReasoningEffort.NONE);
            put(ReasoningEffortEnum.LOW, ReasoningEffort.LOW);
            put(ReasoningEffortEnum.MEDIUM, ReasoningEffort.MEDIUM);
            put(ReasoningEffortEnum.HIGH, ReasoningEffort.HIGH);
            put(ReasoningEffortEnum.ULTRA, ReasoningEffort.XHIGH);

            put(ReasoningEffortEnum.ON, ReasoningEffort.HIGH);
            put(ReasoningEffortEnum.OFF, ReasoningEffort.NONE);
        }
    };

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

    public ProviderResponse sendPrompt(PromptParams promptParams) {
        ResponseService service;
        synchronized (this) {
            service = responseService;
        }
        ArrayList<ResponseInputItem> inputList = new ArrayList<>();
        inputList.add(ResponseInputItem.ofMessage(systemPromptMessage));

        ResponseInputText inputText = ResponseInputText
                .builder()
                .text(promptParams.message())
                .build();
        ResponseInputItem.Message message = ResponseInputItem.Message
                .builder()
                .role(ResponseInputItem.Message.Role.USER)
                .addContent(inputText)
                .build();
        ResponseInputItem responseInputItem = ResponseInputItem.ofMessage(message);
        inputList.add(responseInputItem);
        ResponseCreateParams responseCreateParams = ResponseCreateParams
                .builder()
                .model(promptParams.model())
                .reasoning(Reasoning.builder().effort(RE_MAP.get(promptParams.re())).build())
                .inputOfResponse(inputList)
                .build();
        Response response = service.create(responseCreateParams);

        return new ProviderResponse(getReasoningContent(response), getMessageContent(response));
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
}

package io.github.preschoolller.agentjava.backend.provider.interfaces;

import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderBlockingTextResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderStreamingResponse;

public interface Provider {
    /**
     * api: [response, chatcompletion, messages]
     * @return api + 个人标识
     */
    default String name() {return "";}

    ProviderBlockingTextResponse sendPrompt(PromptParams promptParams);

    ProviderStreamingResponse sendPromptStreaming(PromptParams promptParams);
}

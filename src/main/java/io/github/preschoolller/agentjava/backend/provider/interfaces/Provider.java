package io.github.preschoolller.agentjava.backend.provider.interfaces;

import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderBlockingTextResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderStreamingResponse;

public interface Provider {

    void reload();

    ProviderBlockingTextResponse sendPrompt(PromptParams promptParams);

    ProviderStreamingResponse sendPromptStreaming(PromptParams promptParams);
}

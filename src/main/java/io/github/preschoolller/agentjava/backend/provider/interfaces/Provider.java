package io.github.preschoolller.agentjava.backend.provider.interfaces;

import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderResponse;

public interface Provider {

    void reload();

    ProviderResponse sendPrompt(PromptParams promptParams);
}

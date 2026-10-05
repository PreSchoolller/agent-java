package io.github.preschoolller.agentjava.backend.provider;

import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;

public class ProviderFactory {

    public static Provider createOpenAiProvider() {
        return new OpenAiProvider();
    }
}

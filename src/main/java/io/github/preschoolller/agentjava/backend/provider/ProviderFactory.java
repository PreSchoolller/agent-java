package io.github.preschoolller.agentjava.backend.provider;

import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import io.github.preschoolller.agentjava.frontend.store.Profile;

public class ProviderFactory {

    public static Provider createResponseProvider() {
        return OpenAiResponseProvider.getInstance();
    }

    public static Provider createChatCompletionProvider() {
        return OpenAiChatCompletionProvider.getInstance();
    }

    public static Provider createProvider() {
        String apiType = AppSettings.get().getApiType();
        if (Profile.API_RESPONSE.equals(apiType)) {
            return createResponseProvider();
        }
        if (Profile.API_CHAT_COMPLETION.equals(apiType)) {
            return createChatCompletionProvider();
        }
        return createResponseProvider();
    }
}

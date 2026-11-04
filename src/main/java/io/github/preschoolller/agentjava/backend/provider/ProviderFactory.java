package io.github.preschoolller.agentjava.backend.provider;

import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import io.github.preschoolller.agentjava.frontend.store.Profile;
import org.tinylog.Logger;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class ProviderFactory {

    private static final Map<String, Provider> RESPONSES_PROVIDERS = new ConcurrentHashMap<>();
    private static final Map<String, Provider> CHAT_COMPLETION_PROVIDERS = new ConcurrentHashMap<>();
    private static final Map<String, Provider> MESSAGES_PROVIDERS = new ConcurrentHashMap<>();

    /**
     * @key: Provider.Api
     * @value: Provider, implement() 必须与 key 对应
     */
    private static final Map<Provider.Api, Provider> ACTIVE_PROVIDERS = new ConcurrentHashMap<>();

    private static final AppSettings appSettings = AppSettings.get();

    private ProviderFactory() {}

    public static Provider createResponseProvider() {
        return OpenAiResponseProvider.getInstance();
    }

    public static Provider createChatCompletionProvider() {
        return OpenAiChatCompletionProvider.getInstance();
    }

    public static Provider createProvider() {
        String apiType = appSettings.getApiType();
        if (apiType == null || apiType.isBlank()) {
            apiType = Profile.API_RESPONSE;
        }
        switch (apiType) {
            case Profile.API_RESPONSE -> {
                Provider activeResponses = resolveActiveProvider(Provider.Api.RESPONSES);
                return activeResponses == null ? createResponseProvider() : activeResponses;
            }
            case Profile.API_CHAT_COMPLETION -> {
                Provider activeChatCompletion = resolveActiveProvider(Provider.Api.CHAT_COMPLETION);
                return activeChatCompletion == null ? createChatCompletionProvider() : activeChatCompletion;
            }
            case Profile.API_MESSAGES -> {
                Provider activeMessages = resolveActiveProvider(Provider.Api.MESSAGES);
                if (activeMessages != null) {
                    return activeMessages;
                }
                Logger.warn("found no provider implement /v1/messages, fall back to response");
            }
        }
        return createResponseProvider();
    }

    private static Provider resolveActiveProvider(Provider.Api api) {
        Provider provider = ACTIVE_PROVIDERS.get(api);
        if (provider != null) {
            return provider;
        }
        switch (api) {
            case RESPONSES -> {
                return RESPONSES_PROVIDERS.isEmpty() ? null : setActiveProvider(RESPONSES_PROVIDERS.get(Collections.min(RESPONSES_PROVIDERS.keySet())));
            }
            case CHAT_COMPLETION -> {
                return CHAT_COMPLETION_PROVIDERS.isEmpty() ? null : setActiveProvider(CHAT_COMPLETION_PROVIDERS.get(Collections.min(CHAT_COMPLETION_PROVIDERS.keySet())));
            }
            case MESSAGES -> {
                return MESSAGES_PROVIDERS.isEmpty() ? null : setActiveProvider(MESSAGES_PROVIDERS.get(Collections.min(MESSAGES_PROVIDERS.keySet())));
            }
            default -> {
                return null;
            }
        }
    }

    public static Provider setActiveProvider(Provider provider) {
        if (provider == null || provider.implement() == null) {
            Logger.warn("trying set active provider with wrong type");
            return null;
        }
        Provider value = ACTIVE_PROVIDERS.computeIfAbsent(provider.implement(), key -> provider);
        if (value.equals(provider)) {
            return value;
        }
        return ACTIVE_PROVIDERS.computeIfPresent(provider.implement(), (key, old) -> provider);
    }

    private static void register(Provider provider) {
        if (provider == null || provider.name() == null || provider.name().isBlank() || provider.implement() == null) {
            Logger.info("null provider: ignore");
            return;
        }
        switch (provider.implement()) {
            case RESPONSES -> RESPONSES_PROVIDERS.put(provider.name(), provider);
            case CHAT_COMPLETION -> CHAT_COMPLETION_PROVIDERS.put(provider.name(), provider);
            case MESSAGES -> MESSAGES_PROVIDERS.put(provider.name(), provider);
            default -> throw new IllegalArgumentException("unsupported implementation");
        }
    }

    public static void loadExternal() {
        register(createResponseProvider());
        register(createChatCompletionProvider());
        try {
            Files.createDirectories(Constants.PLUGIN_DIR);
        } catch (IOException e) {
            Logger.error("...-_-!...");
        }
        try (Stream<Path> files = Files.find(Constants.PLUGIN_DIR, 1,
                (path, basicFileAttributes) -> path.getFileName().toString().endsWith(".jar"))) {
            Iterator<Path> iterator = files.iterator();
            while (iterator.hasNext()) {
                Path jar = iterator.next();
                String jarNameWithSuffix = jar.getFileName().toString();
                String jarName = jarNameWithSuffix.substring(0, jarNameWithSuffix.length() - ".jar".length());
                AtomicInteger count = new AtomicInteger();

                try {
                    URL[] jarUrl = new URL[]{jar.toUri().toURL()};
                    URLClassLoader urlClassLoader = new URLClassLoader("plugin-" + jarName, jarUrl, ProviderFactory.class.getClassLoader());
                    ServiceLoader.load(Provider.class, urlClassLoader).forEach(provider -> {
                        register(provider);
                        Logger.info("{} loaded by {}", provider.getClass(), provider.getClass().getClassLoader());
                        count.incrementAndGet();
                    });
                } catch (MalformedURLException e) {
                    Logger.error("should not be here, riiiight ?");
                } catch (Throwable throwable) {
                    Logger.error(throwable, "plugin {} failed", jarNameWithSuffix);
                } finally {
                    Logger.info("plugin {} loaded {} providers", jarNameWithSuffix, count.get());
                }
            }
        } catch (IOException e) {
            Logger.error("load plugin error for: {}", e.getMessage());
        }
    }
}

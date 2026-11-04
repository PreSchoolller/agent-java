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

    public static Provider createProvider(String apiType) {
        if (apiType == null || apiType.isBlank()) {
            apiType = Profile.API_RESPONSE;
        }
        switch (apiType) {
            case Profile.API_RESPONSE -> {
                Provider activeResponses = resolveActiveProvider(Provider.Api.RESPONSES, apiType);
                return activeResponses == null ? createResponseProvider() : activeResponses;
            }
            case Profile.API_CHAT_COMPLETION -> {
                Provider activeChatCompletion = resolveActiveProvider(Provider.Api.CHAT_COMPLETION, apiType);
                return activeChatCompletion == null ? createChatCompletionProvider() : activeChatCompletion;
            }
            case Profile.API_MESSAGES -> {
                Provider activeMessages = resolveActiveProvider(Provider.Api.MESSAGES, apiType);
                if (activeMessages != null) {
                    return activeMessages;
                }
                Logger.warn("found no provider implement /v1/messages, fall back to response");
            }
        }
        return createResponseProvider();
    }

    private static Provider resolveActiveProvider(Provider.Api api, String apiType) {
        Provider provider = ACTIVE_PROVIDERS.get(api);
        if (provider != null) {
            return provider;
        }
        Map<String, Provider> pool = poolOf(api);
        if (pool.isEmpty()) {
            return null;
        }
        // 优先用全局落盘的 provider 名（设置页 Provider 单选，per-apiType 不随方案）；名字失效
        // （插件被删/改名）或未指定时回落字典序最小——与设置页的默认预选一致
        String preferred = appSettings.getActiveProviderName(apiType);
        Provider chosen = preferred == null ? null : pool.get(preferred);
        if (chosen == null) {
            chosen = pool.get(Collections.min(pool.keySet()));
        }
        return setActiveProvider(chosen);
    }

    /** API 类型 → 注册池 */
    private static Map<String, Provider> poolOf(Provider.Api api) {
        return switch (api) {
            case RESPONSES -> RESPONSES_PROVIDERS;
            case CHAT_COMPLETION -> CHAT_COMPLETION_PROVIDERS;
            case MESSAGES -> MESSAGES_PROVIDERS;
        };
    }

    /** Profile.API_* 常量 → Provider.Api；未知值（老文件兜底链之外的意外值）当 response */
    public static Provider.Api apiOf(String apiType) {
        return switch (apiType) {
            case Profile.API_CHAT_COMPLETION -> Provider.Api.CHAT_COMPLETION;
            case Profile.API_MESSAGES -> Provider.Api.MESSAGES;
            default -> Provider.Api.RESPONSES;
        };
    }

    /** 指定 API 类型的注册池只读视图（设置页 ProviderCatalog 读桥用） */
    public static Map<String, Provider> providersOf(Provider.Api api) {
        return Collections.unmodifiableMap(poolOf(api));
    }

    /** 设置页保存后调用：作废全部 active 缓存（Provider 页按 API 类型分组落盘，任何组都可能变了），
     *  下次 createProvider 按落盘名单重新解析 */
    public static void invalidateActiveProvider() {
        ACTIVE_PROVIDERS.clear();
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

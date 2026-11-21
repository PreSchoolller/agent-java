package io.github.preschoolller.agentjava.backend.provider;

import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import io.github.preschoolller.agentjava.frontend.store.Profile;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProviderFactory.class);

    static {
        register(createResponseProvider());
        register(createChatCompletionProvider());
    }

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
                LOG.warn("found no provider implement /v1/messages, fall back to response");
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
            LOG.warn("trying set active provider with wrong type");
            return null;
        }
        Provider value = ACTIVE_PROVIDERS.computeIfAbsent(provider.implement(), key -> provider);
        if (value.equals(provider)) {
            return value;
        }
        return ACTIVE_PROVIDERS.computeIfPresent(provider.implement(), (key, old) -> provider);
    }

    public static void register(Object object) {
        if (!(object instanceof Provider provider)) {
            return;
        }
        if (provider.name() == null || provider.name().isBlank() || provider.implement() == null) {
            LOG.info("null provider: ignore");
            return;
        }
        switch (provider.implement()) {
            case RESPONSES -> RESPONSES_PROVIDERS.put(provider.name(), provider);
            case CHAT_COMPLETION -> CHAT_COMPLETION_PROVIDERS.put(provider.name(), provider);
            case MESSAGES -> MESSAGES_PROVIDERS.put(provider.name(), provider);
            default -> throw new IllegalArgumentException("unsupported implementation");
        }
    }
}

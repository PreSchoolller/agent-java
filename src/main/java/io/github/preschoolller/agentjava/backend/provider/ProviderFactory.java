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
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class ProviderFactory {

    private static volatile Map<String, Provider> providers = new ConcurrentHashMap<>();

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
        if (Profile.API_MESSAGES.equals(apiType)) {
            for (String name : providers.keySet()) {
                if (name.startsWith(Profile.API_MESSAGES)) {
                    return providers.get(name);
                }
            }
        }
        return createResponseProvider();
    }

    private static void register(Provider provider) {
        if (provider == null) {
            Logger.info("null provider: ignore");
            return;
        }
        providers.put(provider.name(), provider);
    }

    public static void loadExternal() {
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

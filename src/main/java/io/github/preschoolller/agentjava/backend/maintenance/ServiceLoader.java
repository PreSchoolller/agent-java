package io.github.preschoolller.agentjava.backend.maintenance;

import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.context.ContextHandlerManager;
import io.github.preschoolller.agentjava.backend.context.interfaces.ContextHandler;
import io.github.preschoolller.agentjava.backend.provider.ProviderFactory;
import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;
import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class ServiceLoader {

    private ServiceLoader() {
    }

    private static final Map<Class<?>, Consumer<Object>> CLASSES = Map.of(
            Tool.class, ToolManager::register,
            Provider.class, ProviderFactory::register,
            ContextHandler.class, ContextHandlerManager::register
    );
    private static final Logger LOG = LoggerFactory.getLogger(ServiceLoader.class);

    public static void load() {
        try {
            Files.createDirectories(Constants.PLUGIN_DIR);
        } catch (IOException e) {
            LOG.error("...-_-!...");
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
                    URLClassLoader urlClassLoader = new URLClassLoader("plugin-" + jarName, jarUrl, ServiceLoader.class.getClassLoader());
                    for (Class<?> aClass : CLASSES.keySet()) {
                        java.util.ServiceLoader.load(aClass, urlClassLoader).forEach(plugin -> {
                            CLASSES.get(aClass).accept(plugin);
                            LOG.info("{} loaded by {}", plugin.getClass(), plugin.getClass().getClassLoader());
                            count.incrementAndGet();
                        });
                    }
                } catch (MalformedURLException e) {
                    LOG.error("should not be here, riiiight ?");
                } catch (Throwable throwable) {
                    LOG.error("plugin {} failed", jarNameWithSuffix, throwable);
                } finally {
                    LOG.info("plugin {} loaded {} tools", jarNameWithSuffix, count.get());
                }
            }
        } catch (IOException e) {
            LOG.error("load plugin error", e);
        }
    }
}

package io.github.preschoolller.agentjava.spi;

import java.util.function.Function;

public class PluginPersistentConsumer {

    private static volatile Function<String, String> persistentProvider = plugin -> null;

    public static void inject(Function<String, String> function) {
        if (function == null) {
            return;
        }
        persistentProvider = function;
    }

    public static String getPersistentInput(String pluginName) {
        return persistentProvider.apply(pluginName);
    }
}

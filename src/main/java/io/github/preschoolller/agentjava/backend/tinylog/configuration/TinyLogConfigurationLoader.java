package io.github.preschoolller.agentjava.backend.tinylog.configuration;

import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import org.tinylog.configuration.ConfigurationLoader;

import java.util.Properties;

public class TinyLogConfigurationLoader implements ConfigurationLoader {
    private static final AppSettings appSettings = AppSettings.get();
    @Override
    public Properties load() {
        Properties properties = new Properties();
        properties.put("writer", "console");
        properties.put("writer.level", appSettings.getLogLevel());
        return properties;
    }
}

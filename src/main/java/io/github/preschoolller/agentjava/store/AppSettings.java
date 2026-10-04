package io.github.preschoolller.agentjava.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 应用设置（单例）：持久化到 ~/.agentjava/settings.properties。
 * 后端要什么配置就往这里加字段，设置面板（SettingsDialog）会跟着补对应输入项。
 */
public final class AppSettings {

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".agentjava", "settings.properties");

    private static AppSettings instance;

    /** 后端接口地址 */
    private String backendUrl = "http://127.0.0.1:8080/chat";
    /** 模型名称 */
    private String model = "default";
    private double temperature = 0.7;
    private int maxTokens = 2048;
    /** 系统提示词 */
    private String systemPrompt = "";
    /** 消息卡片正文字号 */
    private int contentFontSize = 16;

    private final List<Runnable> changeListeners = new ArrayList<>();

    private AppSettings() {
        load();
    }

    public static synchronized AppSettings get() {
        if (instance == null) {
            instance = new AppSettings();
        }
        return instance;
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /** 设置被修改后调用，后端可以在监听里感知配置变化 */
    public void fireChanged() {
        changeListeners.forEach(Runnable::run);
    }

    public String getBackendUrl() {
        return backendUrl;
    }

    public void setBackendUrl(String backendUrl) {
        this.backendUrl = backendUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public int getContentFontSize() {
        return contentFontSize;
    }

    public void setContentFontSize(int contentFontSize) {
        this.contentFontSize = contentFontSize;
    }

    public synchronized void save() {
        Properties properties = new Properties();
        properties.setProperty("backendUrl", backendUrl);
        properties.setProperty("model", model);
        properties.setProperty("temperature", String.valueOf(temperature));
        properties.setProperty("maxTokens", String.valueOf(maxTokens));
        properties.setProperty("systemPrompt", systemPrompt);
        properties.setProperty("contentFontSize", String.valueOf(contentFontSize));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                properties.store(out, "agent-java settings");
            }
        } catch (IOException e) {
            System.err.println("保存设置失败: " + e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(FILE)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            properties.load(in);
        } catch (IOException e) {
            System.err.println("读取设置失败（使用默认值）: " + e.getMessage());
            return;
        }
        backendUrl = properties.getProperty("backendUrl", backendUrl);
        model = properties.getProperty("model", model);
        temperature = parseDouble(properties.getProperty("temperature"), temperature);
        maxTokens = parseInt(properties.getProperty("maxTokens"), maxTokens);
        systemPrompt = properties.getProperty("systemPrompt", systemPrompt);
        contentFontSize = parseInt(properties.getProperty("contentFontSize"), contentFontSize);
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

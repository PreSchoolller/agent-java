package io.github.preschoolller.agentjava.frontend.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 应用设置（单例）：持久化到 ~/.agentjava/settings.properties。
 * 结构：若干套命名配置方案（Profile，后端请求参数）+ 当前方案名 + 全局 UI 偏好（正文字号）。
 * 落盘格式：profileNames（逗号连接保持顺序）、activeProfile、profile.&lt;名&gt;.&lt;字段&gt; 前缀键；
 * v1 之前的平面键（backendUrl/model/...）在 load 时自动迁移进"默认"方案，下次 save 覆写消失。
 * 设置面板（SettingsDialog）编辑方案，后端通过 getActiveProfile()/getBackendUrl() 等委托方法
 * 拿到当前方案的参数，监听 fireChanged 感知变化。
 * 测试用 AppSettings.at(Path) 拿独立实例（不走单例、不碰真实配置文件）。
 */
public final class AppSettings {

    private static final Path FILE = Path.of(System.getProperty("user.home"), ".agentjava", "settings.properties");
    /** 默认方案名：首次启动与旧配置迁移时创建 */
    public static final String DEFAULT_PROFILE_NAME = "默认";

    private static AppSettings instance;

    private final Path file;
    private final Map<String, Profile> profiles = new LinkedHashMap<>();
    private String activeProfileName = DEFAULT_PROFILE_NAME;
    /** 消息卡片正文字号（全局 UI 偏好，不属于任何方案） */
    private int contentFontSize = 16;
    /** 配色主题（全局 UI 偏好）："light" / "dark" */
    private String uiTheme = "light";
    /** 关闭按钮最小化到托盘（全局 UI 偏好，默认关闭=直接退出） */
    private boolean minimizeToTray;

    private AppSettings(Path file) {
        this.file = file;
        load();
    }

    /** 单例入口（读默认配置文件） */
    public static synchronized AppSettings get() {
        if (instance == null) {
            instance = new AppSettings(FILE);
        }
        return instance;
    }

    /** 独立实例（读写指定文件），给回归测试用，与单例互不影响 */
    public static AppSettings at(Path file) {
        return new AppSettings(file);
    }

    // —— 方案管理 ——

    /** 当前方案；名字对不上时兜底取第一个（配置文件被手工改过的情况） */
    public Profile getActiveProfile() {
        Profile profile = profiles.get(activeProfileName);
        if (profile != null) {
            return profile;
        }
        return profiles.values().iterator().next();
    }

    public String getActiveProfileName() {
        return activeProfileName;
    }

    /** 全部方案名，保持列表顺序（侧栏下拉按这个顺序展示） */
    public List<String> getProfileNames() {
        return List.copyOf(profiles.keySet());
    }

    public Profile getProfile(String name) {
        return profiles.get(name);
    }

    /** 当前方案名（切换方案；后端下一次请求起生效） */
    public void setActiveProfileName(String name) {
        if (profiles.containsKey(name)) {
            activeProfileName = name;
        }
    }

    /** 新建方案（复制 source 的参数），名字已存在时返回 false */
    public boolean addProfile(String name, Profile source) {
        String normalized = normalizeName(name);
        if (normalized == null || profiles.containsKey(normalized)) {
            return false;
        }
        profiles.put(normalized, source.copy());
        return true;
    }

    /** 删除方案（至少保留一个，删到最后一个时返回 false） */
    public boolean removeProfile(String name) {
        if (profiles.size() <= 1 || !profiles.containsKey(name)) {
            return false;
        }
        profiles.remove(name);
        if (name.equals(activeProfileName)) {
            activeProfileName = profiles.keySet().iterator().next();
        }
        return true;
    }

    /** 重命名方案（保留原顺序位置），新名字非法或已存在时返回 false */
    public boolean renameProfile(String oldName, String newName) {
        String normalized = normalizeName(newName);
        if (normalized == null || !profiles.containsKey(oldName) || profiles.containsKey(normalized)) {
            return false;
        }
        LinkedHashMap<String, Profile> reordered = new LinkedHashMap<>();
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            if (entry.getKey().equals(oldName)) {
                reordered.put(normalized, entry.getValue());
            } else {
                reordered.put(entry.getKey(), entry.getValue());
            }
        }
        profiles.clear();
        profiles.putAll(reordered);
        if (oldName.equals(activeProfileName)) {
            activeProfileName = normalized;
        }
        return true;
    }

    /** 整体替换方案集合并设定当前方案（设置面板保存时提交工作副本用），随后落盘并通知监听 */
    public void applyProfiles(Map<String, Profile> replacement, String newActiveName) {
        if (replacement.isEmpty()) {
            return;
        }
        profiles.clear();
        for (Map.Entry<String, Profile> entry : replacement.entrySet()) {
            profiles.put(entry.getKey(), entry.getValue().copy());
        }
        activeProfileName = profiles.containsKey(newActiveName)
                ? newActiveName
                : profiles.keySet().iterator().next();
        save();
        fireChanged();
    }

    /** 深拷贝出整套方案（设置面板的工作副本，取消时直接丢弃） */
    public LinkedHashMap<String, Profile> copyProfiles() {
        LinkedHashMap<String, Profile> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    // —— 后端参数委托方法：直接读当前方案，接入后端的代码不用关心方案切换 ——

    public String getBackendUrl() {
        return getActiveProfile().getBackendUrl();
    }

    public String getKey() {
        return getActiveProfile().getKey();
    }

    public List<String> getModels() {
        return getActiveProfile().getModels();
    }

    public String getModel() {
        return getActiveProfile().getModel();
    }

    public double getTemperature() {
        return getActiveProfile().getTemperature();
    }

    public int getMaxTokens() {
        return getActiveProfile().getMaxTokens();
    }

    public String getSystemPrompt() {
        return getActiveProfile().getSystemPrompt();
    }

    // —— 全局 UI 偏好 ——

    public int getContentFontSize() {
        return contentFontSize;
    }

    public void setContentFontSize(int contentFontSize) {
        this.contentFontSize = contentFontSize;
    }

    public String getUiTheme() {
        return uiTheme;
    }

    /** 仅接受 "light"/"dark"，其他值忽略 */
    public void setUiTheme(String uiTheme) {
        if ("light".equals(uiTheme) || "dark".equals(uiTheme)) {
            this.uiTheme = uiTheme;
        }
    }

    public boolean isMinimizeToTray() {
        return minimizeToTray;
    }

    public void setMinimizeToTray(boolean minimizeToTray) {
        this.minimizeToTray = minimizeToTray;
    }

    // —— 变更通知与持久化 ——

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private final List<Runnable> changeListeners = new ArrayList<>();

    /** 设置被修改后调用，后端可以在监听里感知配置变化 */
    public void fireChanged() {
        changeListeners.forEach(Runnable::run);
    }

    public synchronized void save() {
        Properties properties = new Properties();
        properties.setProperty("profileNames", String.join(",", profiles.keySet()));
        properties.setProperty("activeProfile", activeProfileName);
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            String prefix = "profile." + entry.getKey() + ".";
            Profile profile = entry.getValue();
            properties.setProperty(prefix + "backendUrl", profile.getBackendUrl());
            properties.setProperty(prefix + "key", profile.getKey());
            properties.setProperty(prefix + "model", profile.getModel());
            properties.setProperty(prefix + "models", String.join(",", profile.getModels()));
            properties.setProperty(prefix + "temperature", String.valueOf(profile.getTemperature()));
            properties.setProperty(prefix + "maxTokens", String.valueOf(profile.getMaxTokens()));
            properties.setProperty(prefix + "systemPrompt", profile.getSystemPrompt());
        }
        properties.setProperty("contentFontSize", String.valueOf(contentFontSize));
        properties.setProperty("uiTheme", uiTheme);
        properties.setProperty("minimizeToTray", String.valueOf(minimizeToTray));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "agent-java settings");
            }
        } catch (IOException e) {
            System.err.println("保存设置失败: " + e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            profiles.put(DEFAULT_PROFILE_NAME, new Profile());
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            System.err.println("读取设置失败（使用默认值）: " + e.getMessage());
            profiles.put(DEFAULT_PROFILE_NAME, new Profile());
            return;
        }
        contentFontSize = parseInt(properties.getProperty("contentFontSize"), contentFontSize);
        setUiTheme(properties.getProperty("uiTheme"));
        minimizeToTray = Boolean.parseBoolean(properties.getProperty("minimizeToTray", "false"));
        String names = properties.getProperty("profileNames");
        if (names == null) {
            loadLegacyFlat(properties); // v1 之前的平面键格式
        } else {
            for (String name : names.split(",")) {
                String trimmed = name.strip();
                if (!trimmed.isEmpty()) {
                    profiles.put(trimmed, loadProfile(properties, trimmed));
                }
            }
            if (profiles.isEmpty()) {
                profiles.put(DEFAULT_PROFILE_NAME, new Profile());
            }
            String active = properties.getProperty("activeProfile");
            activeProfileName = active != null && profiles.containsKey(active)
                    ? active
                    : profiles.keySet().iterator().next();
        }
    }

    /** 读一个方案的前缀键，缺的字段落回 Profile 默认值 */
    private static Profile loadProfile(Properties properties, String name) {
        String prefix = "profile." + name + ".";
        Profile profile = new Profile();
        profile.setBackendUrl(properties.getProperty(prefix + "backendUrl", profile.getBackendUrl()));
        profile.setKey(properties.getProperty(prefix + "key", profile.getKey()));
        profile.setModel(properties.getProperty(prefix + "model", profile.getModel()));
        // models 缺省（老文件）时用 model 单值兜底成单元素列表；setModels 内部保证 model ∈ models
        String models = properties.getProperty(prefix + "models");
        profile.setModels(models != null && !models.isBlank()
                ? Arrays.stream(models.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList()
                : List.of(profile.getModel()));
        profile.setTemperature(parseDouble(properties.getProperty(prefix + "temperature"), profile.getTemperature()));
        profile.setMaxTokens(parseInt(properties.getProperty(prefix + "maxTokens"), profile.getMaxTokens()));
        profile.setSystemPrompt(properties.getProperty(prefix + "systemPrompt", profile.getSystemPrompt()));
        return profile;
    }

    /** 旧格式迁移：顶层 backendUrl/model/... 平面键整体归入"默认"方案 */
    private void loadLegacyFlat(Properties properties) {
        Profile profile = new Profile();
        profile.setBackendUrl(properties.getProperty("backendUrl", profile.getBackendUrl()));
        profile.setModel(properties.getProperty("model", profile.getModel()));
        profile.setModels(List.of(profile.getModel()));
        profile.setTemperature(parseDouble(properties.getProperty("temperature"), profile.getTemperature()));
        profile.setMaxTokens(parseInt(properties.getProperty("maxTokens"), profile.getMaxTokens()));
        profile.setSystemPrompt(properties.getProperty("systemPrompt", profile.getSystemPrompt()));
        profiles.put(DEFAULT_PROFILE_NAME, profile);
        activeProfileName = DEFAULT_PROFILE_NAME;
    }

    /** 方案名约束：去首尾空白、非空、不含逗号（逗号是 profileNames 的分隔符）；非法返回 null */
    private static String normalizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        return trimmed.isEmpty() || trimmed.contains(",") ? null : trimmed;
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

package io.github.preschoolller.agentjava.frontend.store;

import io.github.preschoolller.agentjava.backend.constant.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用设置（单例）：持久化到 ~/.agentjava/settings.properties。
 * 结构：若干套命名配置方案（Profile，后端请求参数）+ 各 API 类型的 provider 选择 + 全局 UI 偏好（正文字号）。
 * 落盘格式：profileNames（逗号连接保持顺序）、profile.&lt;名&gt;.&lt;字段&gt; 前缀键、activeProvider.&lt;apiType&gt;、
 * activeContextHandler（选用的上下文管理处理器，缺键=不启用）；
 * v1 之前的平面键（backendUrl/model/...）在 load 时自动迁移进"默认"方案，下次 save 覆写消失。
 * 没有活动方案概念：用哪套配置由输入区选中的模型所属方案决定（跨方案路由见 Main.streamReply）。
 * 设置面板（SettingsDialog）编辑方案，监听 fireChanged 感知变化。
 * 测试用 AppSettings.at(Path) 拿独立实例（不走单例、不碰真实配置文件）。
 */
public final class AppSettings {

    private static final Path FILE = Constants.APP_USER_DIR.resolve("settings.properties");
    /** 默认方案名：首次启动与旧配置迁移时创建 */
    public static final String DEFAULT_PROFILE_NAME = "默认";
    public static final String PLUGIN_PROPERTIES = "plugin-properties.";

    private static AppSettings instance;

    private final Path file;
    private final Map<String, Profile> profiles = new LinkedHashMap<>();
    /** 消息卡片正文字号（全局 UI 偏好，不属于任何方案） */
    private int contentFontSize = 16;
    /** 配色主题（全局 UI 偏好）："light" / "dark" / "system"（跟随系统，Ui.resolveTheme 解析） */
    private String uiTheme = "light";
    /** 关闭按钮最小化到托盘（全局 UI 偏好，默认关闭=直接退出） */
    private boolean minimizeToTray;
    /** 审核通知三通道（全局 UI 偏好，默认全开）：工具审批到来时的提醒，独立开关互不牵制 */
    private boolean approvalSound = true;
    private boolean approvalSystemNotify = true;
    private boolean approvalBringToFront = true;
    /** 已勾选的工具名（设置面板"工具"页勾选，后端据此过滤工具声明/执行）。
     *  null = 从未配置，语义为全部启用（未来新注册的工具也自动启用）；空列表 = 显式全不启用 */
    private List<String> enabledTools;
    /** —— 日志设置（设置面板「日志」页，自定义 log.* 键落盘，TinyLogConfigurationLoader 映射成
     *  日志后端属性——以后换后端只改映射，设置页与落盘格式不动。改后重启生效：日志后端
     *  启动时读一次配置）—— */
    /** 控制台输出开关 */
    private boolean logConsoleEnabled = true;
    /** 控制台等级：trace/debug/info/warn/error */
    private String logConsoleLevel = "info";
    /** 控制台格式模式（日志后端的格式占位符）；null = 用后端默认格式 */
    private String logConsoleFormat;
    /** 文件输出开关 */
    private boolean logFileEnabled;
    /** 文件等级 */
    private String logFileLevel = "info";
    /** 日志文件路径（绝对或相对）；null/空白 = 用默认 ~/.agentjava/logs/agent.log（loader 兜底） */
    private String logFilePath;
    /** 文件追加写入：true=续写（默认），false=每次启动覆盖 */
    private boolean logFileAppend = true;
    /** 单文件大小上限（如 10mb），配了即按大小滚动切新文件；null = 不轮转（单文件直写） */
    private String logFileMaxSize;
    /** 滚动后保留的历史文件份数（超出删最旧） */
    private int logFileBackups = 5;
    /** 文件格式模式；null = 用后端默认格式 */
    private String logFileFormat;
    private static final Set<String> LOG_LEVELS = Set.of("trace", "debug", "info", "warn", "error");
    /** 主窗口尺寸/位置与侧栏分隔条位置（全局 UI 偏好；坐标 -1 = 首次启动居中）。
     *  最大化期间的 bounds 不记录，还原后仍是用户习惯的窗口大小 */
    private int windowWidth = 1280;
    private int windowHeight = 720;
    private int windowX = -1;
    private int windowY = -1;
    private int sidebarDivider = -1;

    /** 各 API 类型下选用的 provider 名（Provider 页分组单选落盘，全局不随方案）；缺键=未指定，后端回落默认 */
    private Map<String, String> activeProviderNames = new HashMap<>();

    /** 选用的可修改上下文处理器名（上下文页单选落盘，全局不随方案）；null/空白=不启用，
     *  消息原样发出。名字是否在注册表里由后端消费时兜底 */
    private String activeContextHandler;

    /** 原始键值快照：load() 填充、save() 末尾整体替换（引用原子读，读侧无锁安全）；
     *  默认空表兜全新安装首次读（load 的早退分支不碰它，getPersistentPluginInput 不 NPE） */
    private Properties properties = new Properties();

    /** 工具持久化交互输入的待落盘区：前端交互后写入、save() 合并进 properties 落盘。
     *  ConcurrentHashMap——写入在交互的后台线程、save 可能在别的线程（窗口防抖），防并发改 */
    private Map<String, String> toolProperties = new ConcurrentHashMap<>();

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

    /**
     * 测试隔离入口（GUI 回归用）：把单例整体指向指定文件——回归里的内存改动（钉模型目录/
     * 主题/托盘偏好）连同 Window 的 1 秒防抖落盘（写的是整个设置文件）都只落这份临时文件，
     * 真实 ~/.agentjava/settings.properties 从此读不到也写不着。须在任何窗口/组件构造前
     * 调用（组件构造后仍每次经 get() 取值，晚调只影响调用前已读的快照）。
     */
    public static synchronized void installForTest(Path file) {
        instance = new AppSettings(file);
    }

    // —— 方案管理 ——

    /** 全部方案名，保持列表顺序（输入区模型下拉按这个顺序展开） */
    public List<String> getProfileNames() {
        return List.copyOf(profiles.keySet());
    }

    public Profile getProfile(String name) {
        return profiles.get(name);
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
        return true;
    }

    /** 整体替换方案集合（设置面板保存时提交工作副本用），随后落盘并通知监听 */
    public void applyProfiles(Map<String, Profile> replacement) {
        if (replacement.isEmpty()) {
            return;
        }
        profiles.clear();
        for (Map.Entry<String, Profile> entry : replacement.entrySet()) {
            profiles.put(entry.getKey(), entry.getValue().copy());
        }
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

    // —— 跨方案查询：没有活动方案概念，用哪套配置由调用方按业务决定（如输入区选中的模型） ——

    /** 跨方案找模型元数据（输入区下拉已跨方案列模型，思考强度带出用）；同名取先注册的方案，找不到 null */
    public ModelMeta findModelMeta(String model) {
        if (model == null) {
            return null;
        }
        for (Profile profile : profiles.values()) {
            for (ModelMeta meta : profile.getModelMetas()) {
                if (meta.getName().equals(model)) {
                    return meta;
                }
            }
        }
        return null;
    }

    /** 拥有该模型名的方案（输入区跨方案选模型后，请求应路由到它）；没有方案拥有返回 null */
    public Profile findProfileOwningModel(String model) {
        if (model == null) {
            return null;
        }
        for (Profile profile : profiles.values()) {
            if (profile.containsModel(model)) {
                return profile;
            }
        }
        return null;
    }

    /** 各 API 类型下选用的 provider 名（Provider 页分组单选落盘，全局不随方案）；缺键=未指定，后端回落默认 */
    public String getActiveProviderName(String apiType) {
        return activeProviderNames.get(apiType);
    }

    /** 全部 API 类型的 provider 选择（只读视图） */
    public Map<String, String> getActiveProviderNames() {
        return Map.copyOf(activeProviderNames);
    }

    /** 设置某 API 类型下的 provider 选择；null/空白=清除该类型的指定。名字是否在注册表里由后端解析时兜底 */
    public void setActiveProviderName(String apiType, String name) {
        if (apiType == null || !Profile.API_TYPES.contains(apiType)) {
            return; // 未知类型丢弃：落盘只认三种已知类型
        }
        if (name == null || name.isBlank()) {
            activeProviderNames.remove(apiType);
        } else {
            activeProviderNames.put(apiType, name.strip());
        }
    }

    /** 选用的可修改上下文处理器名（上下文页单选落盘，全局不随方案）；null/空白=不启用 */
    public String getActiveContextHandler() {
        return activeContextHandler;
    }

    /** 设置选用的上下文处理器；null/空白=不启用（清除）。名字是否在注册表里由后端消费时兜底 */
    public void setActiveContextHandler(String name) {
        activeContextHandler = name == null || name.isBlank() ? null : name.strip();
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

    public boolean isLogConsoleEnabled() {
        return logConsoleEnabled;
    }

    public void setLogConsoleEnabled(boolean logConsoleEnabled) {
        this.logConsoleEnabled = logConsoleEnabled;
    }

    public String getLogConsoleLevel() {
        return logConsoleLevel;
    }

    /** 仅接受 trace/debug/info/warn/error，其他值忽略 */
    public void setLogConsoleLevel(String level) {
        if (level != null && LOG_LEVELS.contains(level)) {
            logConsoleLevel = level;
        }
    }

    public String getLogConsoleFormat() {
        return logConsoleFormat;
    }

    /** 格式模式：空白 = 恢复用后端默认格式 */
    public void setLogConsoleFormat(String format) {
        logConsoleFormat = format == null || format.isBlank() ? null : format.strip();
    }

    public boolean isLogFileEnabled() {
        return logFileEnabled;
    }

    public void setLogFileEnabled(boolean logFileEnabled) {
        this.logFileEnabled = logFileEnabled;
    }

    public String getLogFileLevel() {
        return logFileLevel;
    }

    /** 仅接受 trace/debug/info/warn/error，其他值忽略 */
    public void setLogFileLevel(String level) {
        if (level != null && LOG_LEVELS.contains(level)) {
            logFileLevel = level;
        }
    }

    public String getLogFilePath() {
        return logFilePath;
    }

    /** 文件路径：空白 = 恢复默认路径（loader 兜底） */
    public void setLogFilePath(String path) {
        logFilePath = path == null || path.isBlank() ? null : path.strip();
    }

    public boolean isLogFileAppend() {
        return logFileAppend;
    }

    public void setLogFileAppend(boolean logFileAppend) {
        this.logFileAppend = logFileAppend;
    }

    public String getLogFileMaxSize() {
        return logFileMaxSize;
    }

    /** 单文件上限：空白 = 不轮转；填了必须带 kb/mb/gb 单位（isValidLogFileSize），非法值忽略 */
    public void setLogFileMaxSize(String maxSize) {
        if (maxSize == null || maxSize.isBlank()) {
            logFileMaxSize = null;
            return;
        }
        if (isValidLogFileSize(maxSize)) {
            logFileMaxSize = maxSize.strip();
        }
    }

    /**
     * 单文件上限格式校验：数字 + kb/mb/gb 单位（大小写均可），如 10mb / 512KB。
     * 值会拼进滚动策略 "size: …" 交给日志后端解析，存入前先拦一道明显不合法的。
     */
    public static boolean isValidLogFileSize(String maxSize) {
        return maxSize != null && maxSize.strip().matches("\\d+(?i)(kb|mb|gb)");
    }

    public int getLogFileBackups() {
        return logFileBackups;
    }

    /** 保留份数：钳到 1..999（0 无意义，过大没谱） */
    public void setLogFileBackups(int backups) {
        logFileBackups = Math.max(1, Math.min(999, backups));
    }

    public String getLogFileFormat() {
        return logFileFormat;
    }

    /** 格式模式：空白 = 恢复用后端默认格式 */
    public void setLogFileFormat(String format) {
        logFileFormat = format == null || format.isBlank() ? null : format.strip();
    }

    /** 仅接受 "light"/"dark"，其他值忽略 */
    public void setUiTheme(String uiTheme) {
        if ("light".equals(uiTheme) || "dark".equals(uiTheme) || "system".equals(uiTheme)) {
            this.uiTheme = uiTheme;
        }
    }

    private static Boolean systemDark; // 系统深浅色探测一次缓存

    /**
     * 主题偏好解析成实际主题（Ui.applyTheme 用）："system" 跟随系统——Windows 读注册表
     * AppsUseLightTheme，非 Windows/失败按浅色；其余按字面。
     */
    public String resolvedUiTheme() {
        if ("system".equals(uiTheme)) {
            if (systemDark == null) {
                systemDark = probeSystemDark();
            }
            return systemDark ? "dark" : "light";
        }
        return "dark".equals(uiTheme) ? "dark" : "light";
    }

    /** 探测 Windows 系统应用主题：输出按 ISO-8859-1 解——关键 token 全是 ASCII，
     *  任何单字节解码都不破坏匹配 */
    private static boolean probeSystemDark() {
        try {
            Process process = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").start();
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
            process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)")
                    .matcher(output);
            return matcher.find() && "0".equals(matcher.group(1));
        } catch (Exception e) {
            return false; // 非 Windows / reg 不可用：按浅色
        }
    }

    public boolean isMinimizeToTray() {
        return minimizeToTray;
    }

    public void setMinimizeToTray(boolean minimizeToTray) {
        this.minimizeToTray = minimizeToTray;
    }

    public boolean isApprovalSound() {
        return approvalSound;
    }

    public void setApprovalSound(boolean approvalSound) {
        this.approvalSound = approvalSound;
    }

    public boolean isApprovalSystemNotify() {
        return approvalSystemNotify;
    }

    public void setApprovalSystemNotify(boolean approvalSystemNotify) {
        this.approvalSystemNotify = approvalSystemNotify;
    }

    public boolean isApprovalBringToFront() {
        return approvalBringToFront;
    }

    public void setApprovalBringToFront(boolean approvalBringToFront) {
        this.approvalBringToFront = approvalBringToFront;
    }

    /**
     * 已勾选的工具名列表：设置面板"工具"页的勾选结果（落盘 enabledTools，逗号连接）。
     * 未配置过（null）时语义为全部启用，透传 null
     * 空列表表示全部禁用，历史用过的为保持一致性后端仍然启用
     * @return nullable
     */
    public List<String> getEnabledTools() {
        if (enabledTools != null) {
            return List.copyOf(enabledTools);
        }
        return null;
    }

    /** 某个工具当前是否勾选：未配置视为全部启用 */
    public boolean isToolEnabled(String toolName) {
        return enabledTools == null || enabledTools.contains(toolName);
    }

    /** 写工具勾选（设置面板保存时提交）：null 表示恢复"未配置=全部启用" */
    public void setEnabledTools(List<String> names) {
        enabledTools = names == null ? null : List.copyOf(names);
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(int windowWidth) {
        this.windowWidth = windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(int windowHeight) {
        this.windowHeight = windowHeight;
    }

    public int getWindowX() {
        return windowX;
    }

    public void setWindowX(int windowX) {
        this.windowX = windowX;
    }

    public int getWindowY() {
        return windowY;
    }

    public void setWindowY(int windowY) {
        this.windowY = windowY;
    }

    public int getSidebarDivider() {
        return sidebarDivider;
    }

    public void setSidebarDivider(int sidebarDivider) {
        this.sidebarDivider = sidebarDivider;
    }

    // —— 工作区 ——

    /**
     * 默认工作区目录：~/.agentjava/workspace。新建对话不选工作区时所有工具的相对路径落这里；
     * 默认位置以后要换就改这一处（会话没绑过工作区的会跟着走）。
     */
    public static Path defaultWorkspace() {
        return Constants.DEFAULT_WORKSPACE_DIR;
    }

    /** 工作区路径规范化：去首尾空白、转绝对路径、去冗余段；空串/非法路径返回 null */
    public static String normalizeWorkspace(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Path.of(trimmed).toAbsolutePath().normalize().toString();
        } catch (Exception e) { // InvalidPathException 等：Windows 下保留字符也会走到这
            return null;
        }
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
        for (Map.Entry<String, Profile> entry : profiles.entrySet()) {
            String prefix = "profile." + entry.getKey() + ".";
            Profile profile = entry.getValue();
            properties.setProperty(prefix + "backendUrl", profile.getBackendUrl());
            properties.setProperty(prefix + "key", profile.getKey());
            properties.setProperty(prefix + "model", profile.getModel());
            // 模型目录（含元数据）整块序列化；逗号列在 JSON 里不影响属性值边界（properties 值不截断）
            StringBuilder metasJson = new StringBuilder("[");
            List<ModelMeta> metas = profile.getModelMetas();
            for (int i = 0; i < metas.size(); i++) {
                if (i > 0) {
                    metasJson.append(',');
                }
                metasJson.append(JsonKit.write(metas.get(i).toJson()));
            }
            metasJson.append(']');
            properties.setProperty(prefix + "modelsMeta", metasJson.toString());
            properties.setProperty(prefix + "apiType", profile.getApiType());
            properties.setProperty(prefix + "temperature", String.valueOf(profile.getTemperature()));
            properties.setProperty(prefix + "maxTokens", String.valueOf(profile.getMaxTokens()));
            properties.setProperty(prefix + "systemPrompt", profile.getSystemPrompt());
        }
        properties.setProperty("contentFontSize", String.valueOf(contentFontSize));
        properties.setProperty("uiTheme", uiTheme);
        properties.setProperty("minimizeToTray", String.valueOf(minimizeToTray));
        properties.setProperty("approvalSound", String.valueOf(approvalSound));
        properties.setProperty("approvalSystemNotify", String.valueOf(approvalSystemNotify));
        properties.setProperty("approvalBringToFront", String.valueOf(approvalBringToFront));
        if (enabledTools != null) {
            properties.setProperty("enabledTools", String.join(",", enabledTools));
        }
        // 各 API 类型的 provider 选择（全局，不随方案；Provider 页分组单选）：缺键=该类型未指定
        for (Map.Entry<String, String> providerEntry : activeProviderNames.entrySet()) {
            properties.setProperty("activeProvider." + providerEntry.getKey(), providerEntry.getValue());
        }
        // 上下文管理处理器选择（全局，不随方案；上下文页单选）：缺键=不启用
        if (activeContextHandler != null) {
            properties.setProperty("activeContextHandler", activeContextHandler);
        }

        properties.setProperty("windowWidth", String.valueOf(windowWidth));
        properties.setProperty("windowHeight", String.valueOf(windowHeight));
        properties.setProperty("windowX", String.valueOf(windowX));
        properties.setProperty("windowY", String.valueOf(windowY));
        properties.setProperty("sidebarDivider", String.valueOf(sidebarDivider));
        properties.setProperty("log.console.enabled", String.valueOf(logConsoleEnabled));
        properties.setProperty("log.console.level", logConsoleLevel);
        if (logConsoleFormat != null) { // 缺键=用后端默认格式
            properties.setProperty("log.console.format", logConsoleFormat);
        }
        properties.setProperty("log.file.enabled", String.valueOf(logFileEnabled));
        properties.setProperty("log.file.level", logFileLevel);
        if (logFilePath != null) { // 缺键=用默认路径（loader 兜底）
            properties.setProperty("log.file.path", logFilePath);
        }
        properties.setProperty("log.file.append", String.valueOf(logFileAppend));
        if (logFileMaxSize != null) { // 缺键=不轮转（单文件直写）
            properties.setProperty("log.file.maxSize", logFileMaxSize);
        }
        properties.setProperty("log.file.backups", String.valueOf(logFileBackups));
        if (logFileFormat != null) { // 缺键=用后端默认格式
            properties.setProperty("log.file.format", logFileFormat);
        }
        if (toolProperties != null) {
            for (String key : toolProperties.keySet()) {
                if (key == null) {
                    continue;
                }
                properties.put(key, toolProperties.get(key));
            }
        }
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "agent-java settings");
            }
            this.properties = properties;
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
        this.properties = properties;
        contentFontSize = parseInt(properties.getProperty("contentFontSize"), contentFontSize);
        windowWidth = parseInt(properties.getProperty("windowWidth"), windowWidth);
        windowHeight = parseInt(properties.getProperty("windowHeight"), windowHeight);
        windowX = parseInt(properties.getProperty("windowX"), windowX);
        windowY = parseInt(properties.getProperty("windowY"), windowY);
        sidebarDivider = parseInt(properties.getProperty("sidebarDivider"), sidebarDivider);
        setUiTheme(properties.getProperty("uiTheme"));
        minimizeToTray = Boolean.parseBoolean(properties.getProperty("minimizeToTray", "false"));
        // 审核通知三开关：缺键（旧文件/手工改坏）默认开——提醒缺了比响了更没人发现
        approvalSound = Boolean.parseBoolean(properties.getProperty("approvalSound", "true"));
        approvalSystemNotify = Boolean.parseBoolean(properties.getProperty("approvalSystemNotify", "true"));
        approvalBringToFront = Boolean.parseBoolean(properties.getProperty("approvalBringToFront", "true"));
        // 工具勾选：缺键=从未配置（全部启用）；空值=显式全不启用（拆开存，两者语义不同）
        String enabledToolsRaw = properties.getProperty("enabledTools");
        enabledTools = enabledToolsRaw == null ? null
                : Arrays.stream(enabledToolsRaw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        // 各 API 类型的 provider 选择（全局，不随方案）：缺键/空白=未指定
        activeProviderNames = new HashMap<>();
        for (String apiType : Profile.API_TYPES) {
            String providerName = properties.getProperty("activeProvider." + apiType);
            if (providerName != null && !providerName.isBlank()) {
                activeProviderNames.put(apiType, providerName.strip());
            }
        }
        // 上下文管理处理器选择（全局，不随方案）：缺键/空白=不启用
        String contextHandler = properties.getProperty("activeContextHandler");
        activeContextHandler = contextHandler == null || contextHandler.isBlank() ? null : contextHandler.strip();
        // 日志设置：等级非法值落默认；旧版全局 logLevel 键迁移成控制台等级（新键缺位时才用）
        logConsoleEnabled = Boolean.parseBoolean(properties.getProperty("log.console.enabled", "true"));
        String consoleLevel = properties.getProperty("log.console.level");
        if (consoleLevel == null) {
            consoleLevel = properties.getProperty("logLevel"); // 旧版平面键迁移
        }
        logConsoleLevel = consoleLevel != null && LOG_LEVELS.contains(consoleLevel) ? consoleLevel : "info";
        String consoleFormat = properties.getProperty("log.console.format");
        logConsoleFormat = consoleFormat == null || consoleFormat.isBlank() ? null : consoleFormat;
        logFileEnabled = Boolean.parseBoolean(properties.getProperty("log.file.enabled", "false"));
        String fileLevel = properties.getProperty("log.file.level");
        logFileLevel = fileLevel != null && LOG_LEVELS.contains(fileLevel) ? fileLevel : "info";
        String filePath = properties.getProperty("log.file.path");
        logFilePath = filePath == null || filePath.isBlank() ? null : filePath;
        logFileAppend = Boolean.parseBoolean(properties.getProperty("log.file.append", "true"));
        // maxSize 手改坏值（无单位等）不入库——loader 拼进滚动策略前会拦，这里直接当未配置
        String fileSize = properties.getProperty("log.file.maxSize");
        logFileMaxSize = fileSize != null && isValidLogFileSize(fileSize) ? fileSize.strip() : null;
        logFileBackups = Math.max(1, Math.min(999, parseInt(properties.getProperty("log.file.backups"), 5)));
        String fileFormat = properties.getProperty("log.file.format");
        logFileFormat = fileFormat == null || fileFormat.isBlank() ? null : fileFormat;
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
        }
        // 插件持久化交互输入（plugin-properties.<插件name()>）：泛化键不逐个解析，整段收进
        // 待落盘 map。必须回填——save() 是"从模型重建整份文件"，只留在 properties 快照里的
        // 键活不过启动后第一次防抖落盘（现象：关应用时文件里有、重启后被自己的 save 抹掉）
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(PLUGIN_PROPERTIES) && key.length() > PLUGIN_PROPERTIES.length()) {
                toolProperties.put(key, properties.getProperty(key));
            }
        }
    }

    /** 读一个方案的前缀键，缺的字段落回 Profile 默认值 */
    private static Profile loadProfile(Properties properties, String name) {
        String prefix = "profile." + name + ".";
        Profile profile = new Profile();
        profile.setBackendUrl(properties.getProperty(prefix + "backendUrl", profile.getBackendUrl()));
        profile.setKey(properties.getProperty(prefix + "key", profile.getKey()));
        profile.setModel(properties.getProperty(prefix + "model", profile.getModel()));
        // 模型目录：新键 modelsMeta 整块 JSON；缺键（老文件）用旧逗号 models 键兜底，再缺用默认项单元素
        String modelsMeta = properties.getProperty(prefix + "modelsMeta");
        if (modelsMeta != null && !modelsMeta.isBlank()) {
            List<ModelMeta> metas = new ArrayList<>();
            for (Object item : (List<?>) JsonKit.parse(modelsMeta)) {
                // 名字空白（手工编辑坏条目）直接丢弃，目录里留空名只会让下拉出空白项
                ModelMeta meta = ModelMeta.fromJson((Map<String, Object>) item);
                if (meta.getName() != null && !meta.getName().isBlank()) {
                    metas.add(meta);
                }
            }
            if (metas.isEmpty()) {
                metas.add(new ModelMeta(profile.getModel()));
            }
            profile.setModelMetas(metas);
        } else {
            String models = properties.getProperty(prefix + "models");
            profile.setModels(models != null && !models.isBlank()
                    ? Arrays.stream(models.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList()
                    : List.of(profile.getModel()));
        }
        // apiType 缺省（老文件）时保持字段默认 response；非法值在 setApiType 里回落
        profile.setApiType(properties.getProperty(prefix + "apiType", profile.getApiType()));
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
    }

    /** 方案名约束：去首尾空白、非空、不含逗号（逗号是 profileNames 的分隔符）；非法返回 null */
    private static String normalizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        return trimmed.isEmpty() || trimmed.contains(",") ? null : trimmed;
    }

    /** 数值键解析：缺键/非法值都落 fallback（properties 缺键返回 null，Double.parseDouble 炸 NPE，
     *  手写/半新设置文件不能因为少一个键就起不来） */
    private static double parseDouble(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 读插件的持久化交互输入（键 plugin-properties.前缀 + 插件 name()，后端经
     * PluginManager.getPersistentPluginInput → PluginBase.getPersistentInput 消费）。读的是最近一次
     * save() 后的快照——前端交互后 set + save 成对调用，保证下次读得见。
     * @param pluginName 插件名（插件注册键，比运行时类名稳：不受包装/代理影响）
     * @return 未配置返回 null（注意空串是合法值，语义由各插件自己的解释器定）
     */
    public String getPersistentPluginInput(String pluginName) {
        return properties.getProperty(PLUGIN_PROPERTIES + pluginName);
    }

    /**
     * 插件的持久化交互输入（前端交互成功后调用，随后必须 save() 才落盘——
     * 写只进待落盘区，save 时合并进 properties）。null 值不允许（Properties 语义），
     * 清除语义=存空串，解释权在插件侧。
     */
    public void setPersistentPluginInput(String pluginName, String input) {
        toolProperties.put(PLUGIN_PROPERTIES + pluginName, input);
    }
}

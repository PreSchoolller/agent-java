package io.github.preschoolller.agentjava.frontend.store;

import io.github.preschoolller.agentjava.backend.constant.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    private static final Path FILE = Constants.APP_USER_DIR.resolve("settings.properties");
    /** 默认方案名：首次启动与旧配置迁移时创建 */
    public static final String DEFAULT_PROFILE_NAME = "默认";

    private static AppSettings instance;

    private final Path file;
    private final Map<String, Profile> profiles = new LinkedHashMap<>();
    private String activeProfileName = DEFAULT_PROFILE_NAME;
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
    /** TinyLog日志等级，修改需重启生效 */
    private String logLevel = "info";
    private static final Set<String> allowLevels = Set.of("trace", "debug", "info", "warn", "error");
    /** 主窗口尺寸/位置与侧栏分隔条位置（全局 UI 偏好；坐标 -1 = 首次启动居中）。
     *  最大化期间的 bounds 不记录，还原后仍是用户习惯的窗口大小 */
    private int windowWidth = 1280;
    private int windowHeight = 720;
    private int windowX = -1;
    private int windowY = -1;
    private int sidebarDivider = -1;

    /** bash 工具的转文件匹配正则（设置面板「工具」页）：命令文本命中即改写成临时文件后
     *  bash 执行，替代 echo base64 | base64 -d | bash 管道。null = 未配置（仅超长命令转文件）。
     *  只收合法且安全的正则（见 isValidBashFilePattern），非法值不落盘也不设值 */
    private String bashFilePattern;
    /** 转文件正则的长度帽：文件名启发式用不了多长，超长只可能是在塞回溯炸弹 */
    private static final int BASH_PATTERN_MAX_LENGTH = 256;

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

    /** 活动方案里按名字找模型元数据（输入区"默认推理等级"带出用）；找不到返回 null */
    public ModelMeta getModelMeta(String model) {
        if (model == null) {
            return null;
        }
        for (ModelMeta meta : getActiveProfile().getModelMetas()) {
            if (meta.getName().equals(model)) {
                return meta;
            }
        }
        return null;
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

    public String getApiType() {
        return getActiveProfile().getApiType();
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

    public String getLogLevel() {
        return logLevel;
    }

    public String getBashFilePattern() {
        return bashFilePattern;
    }

    /** 写 bash 转文件正则：空白 = 清除配置（回到"仅超长命令转文件"默认）；非空时只收
     *  合法且安全的正则——非法/不安全值忽略不设值（旧值保持，调用方无需先自行校验） */
    public void setBashFilePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            bashFilePattern = null;
            return;
        }
        if (isValidBashFilePattern(pattern)) {
            bashFilePattern = pattern.strip();
        }
    }

    /**
     * 转文件正则的"正常且安全"校验：非空白、长度在帽内、能编译、无灾难性回溯的嵌套
     * 无限量词。这条正则会被 bash 工具拿去逐条命令做 find——回溯炸弹（(a+)+ 这类）会让
     * 每次工具调用都卡在正则引擎里，所以存入前拦下。嵌套量词检测是保守启发式（覆盖常见
     * 炸弹形态，不追求识别全部 ReDoS；误杀的写法换等价写法即可）。
     */
    public static boolean isValidBashFilePattern(String pattern) {
        if (pattern == null) {
            return false;
        }
        String trimmed = pattern.strip();
        if (trimmed.isEmpty() || trimmed.length() > BASH_PATTERN_MAX_LENGTH) {
            return false;
        }
        try {
            Pattern.compile(trimmed);
        } catch (PatternSyntaxException e) {
            return false;
        }
        return !hasNestedUnboundedQuantifier(trimmed);
    }

    /**
     * 嵌套无限量词扫描：含无限量词（* / + / {n,}）的分组又被无限量词修饰（(a+)+、(a|b+)*
     * 等）判为不安全。逐字符走一遍，跳过转义符与字符类 []（里面的 * + { } 是字面量）；
     * {@code {n,m}} 有上界的量词回溯有限，不算。已通过 Pattern.compile，扫到的 { 一定是合法量词。
     */
    private static boolean hasNestedUnboundedQuantifier(String pattern) {
        Deque<Boolean> stack = new ArrayDeque<>(); // 外层各分组"是否已出现过无限量词"
        boolean groupStarvable = false; // 当前分组内是否出现过无限量词
        boolean justClosedStarvable = false; // 前一个字符刚闭合的分组是否含无限量词（量词修饰的对象）
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            boolean appliesToStarvableGroup = justClosedStarvable; // 本字符若是量词，修饰它
            justClosedStarvable = false; // 闭口标志只活一步：到不了紧跟其后的量词就没用了
            if (c == '\\') {
                i++; // 转义：连下一个字符一起吃掉
                continue;
            }
            if (c == '[') { // 字符类整段跳过（内部 \] 转义由上面的 \\ 分支处理）
                while (++i < pattern.length() && pattern.charAt(i) != ']') {
                    if (pattern.charAt(i) == '\\') {
                        i++;
                    }
                }
                continue;
            }
            if (c == '(') {
                stack.push(groupStarvable);
                groupStarvable = false; // 进组重置：组内量词记到组自己头上
                continue;
            }
            if (c == ')') {
                justClosedStarvable = groupStarvable;
                Boolean parent = stack.poll();
                groupStarvable = parent != null && (parent || justClosedStarvable); // 子组的量词算外层体内的
                continue;
            }
            boolean unbounded;
            int next = i + 1;
            if (c == '*' || c == '+') {
                unbounded = true;
            } else if (c == '{') { // {n,} 无上界=无限量词；{n}/{n,m} 有界不算
                int close = pattern.indexOf('}', i);
                if (close < 0) { // 编译已拦截游离 {，走到这只是防御：按普通字符继续
                    unbounded = false;
                    next = i + 1;
                } else {
                    unbounded = pattern.substring(i + 1, close).matches("\\d*,");
                    next = close + 1;
                }
            } else {
                unbounded = false;
            }
            if (unbounded) {
                if (appliesToStarvableGroup) {
                    return true; // 含无限量词的分组又被无限量词修饰：回溯炸弹
                }
                groupStarvable = true;
            }
            i = next - 1; // 量词可能是多字符（{2,}），推进到量词末尾
        }
        return false;
    }

    /** 仅接受 trace/debug/info/warn/error，其他值忽略（TinyLog 重启读一次，改了不重启不生效） */
    public void setLogLevel(String logLevel) {
        if (logLevel != null && allowLevels.contains(logLevel)) {
            this.logLevel = logLevel;
        }
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
     * 未配置过（null）时语义为全部启用，当场经 ToolCatalog 反射取注册表全名——
     * 注册表拿不出来（裸类路径回归，踩坑 #26 语义）时退空列表，调用方按空=未配置兜底。
     * 已配置时返回存值副本（含显式空列表=全不启用，调用方原样消费即可）。
     */
    public List<String> getEnabledTools() {
        if (enabledTools != null) {
            return List.copyOf(enabledTools);
        }
        return ToolCatalog.registeredNames();
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
        properties.setProperty("activeProfile", activeProfileName);
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

        properties.setProperty("windowWidth", String.valueOf(windowWidth));
        properties.setProperty("windowHeight", String.valueOf(windowHeight));
        properties.setProperty("windowX", String.valueOf(windowX));
        properties.setProperty("windowY", String.valueOf(windowY));
        properties.setProperty("sidebarDivider", String.valueOf(sidebarDivider));
        properties.setProperty("logLevel", String.valueOf(logLevel));
        if (bashFilePattern != null) { // 缺键=未配置；非法值在 load/setter 两处已拦下，落盘的必是校验过的
            properties.setProperty("bashFilePattern", bashFilePattern);
        }
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
        // 转文件正则：手改配置文件塞进来的坏值（编译不过/回溯炸弹/超长）不入库——直接当未配置。
        // "something" 是旧版无条件落盘的占位默认值（从未有过前端入口，不可能是用户的选择），一并迁移掉
        String patternRaw = properties.getProperty("bashFilePattern");
        bashFilePattern = patternRaw != null && !"something".equals(patternRaw.strip())
                && isValidBashFilePattern(patternRaw) ? patternRaw.strip() : null;
        logLevel = properties.getProperty("logLevel");
        if (logLevel == null || !allowLevels.contains(logLevel)) {
            logLevel = "info";
        }
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
}

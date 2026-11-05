package io.github.preschoolller.agentjava.frontend.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置方案单元测试（原 foragent/tools/Shot.java 的 SETTINGS 段平移）。
 * AppSettings.at 独立实例，不碰真实 ~/.agentjava/settings.properties；每个用例独立文件互不依赖。
 */
class AppSettingsTest {

    @TempDir
    Path dir;

    private Path file(String name) {
        return dir.resolve(name);
    }

    private static Properties properties(String... keyValues) throws Exception {
        Properties props = new Properties();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            props.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return props;
    }

    private static void store(Properties props, Path file) throws Exception {
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "");
        }
    }

    @Test
    void t01_profileAndModelMetaRoundTrip() {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        assertEquals(List.of("默认"), testSettings.getProfileNames(), "首启默认方案");
        testSettings.getProfile("默认").setModel("test-model");
        // 可选模型列表：设置→落盘→重读→默认项不在列表里时落到第一个
        testSettings.getProfile("默认").setModels(List.of("model-a", "model-b"));
        testSettings.getProfile("默认").setModel("model-b");
        assertEquals("model-b", testSettings.getProfile("默认").getModel(), "默认项在列表里保持");
        testSettings.getProfile("默认").setModels(List.of("model-c"));
        assertEquals("model-c", testSettings.getProfile("默认").getModel(), "默认项不在列表里落到第一个");
        testSettings.getProfile("默认").setModels(List.of("model-a", "model-b"));
        testSettings.getProfile("默认").setModel("model-b");
        // 模型目录元数据（计划外）：名字/上下文/最大输出/推理等级/输入能力整块落盘往返
        ModelMeta richA = new ModelMeta("model-a");
        richA.setCtxWindow("256K");
        richA.setMaxOutput("32K");
        richA.setReasoning("HIGH");
        richA.setText(true);
        ModelMeta richB = new ModelMeta("model-b");
        richB.setImage(true);
        testSettings.getProfile("默认").setModelMetas(List.of(richA, richB));
        testSettings.addProfile("写作", testSettings.getProfile("默认"));
        testSettings.getProfile("写作").setTemperature(0.3);
        testSettings.getProfile("写作").setApiType("chatcompletion");
        assertFalse(testSettings.addProfile("写作", testSettings.getProfile("默认")), "重名方案拒绝");
        testSettings.save();
        AppSettings reloaded = AppSettings.at(settingsFile);
        assertEquals(List.of("默认", "写作"), reloaded.getProfileNames(), "方案列表往返");
        assertEquals(0.3, reloaded.getProfile("写作").getTemperature(), 1e-12, "方案参数往返");
        assertEquals("model-b", reloaded.getProfile("默认").getModel(), "首个方案参数往返");
        assertEquals(List.of("model-a", "model-b"), reloaded.getProfile("写作").getModels(), "模型列表往返");
        assertEquals("model-b", reloaded.getProfile("写作").getModel(), "默认模型往返");
        ModelMeta savedA = reloaded.getProfile("写作").getModelMetas().get(0);
        assertTrue("model-a".equals(savedA.getName()) && "256K".equals(savedA.getCtxWindow())
                && "32K".equals(savedA.getMaxOutput()) && "HIGH".equals(savedA.getReasoning())
                && savedA.isText() && !savedA.isImage(), "模型元数据往返");
        assertTrue(reloaded.getProfile("写作").getModelMetas().get(1).isImage(), "模型图片能力往返");
        assertTrue("HIGH".equals(testSettings.findModelMeta("model-a").normalizedReasoning())
                && testSettings.findModelMeta("不存在") == null
                && testSettings.findModelMeta(null) == null, "跨方案模型元数据查找与非法推理等级回落");
        // API 类型（计划外，后端待支持）：写值往返；未设置的方案与旧文件缺键都是默认 response
        assertEquals("chatcompletion", reloaded.getProfile("写作").getApiType(), "API 类型往返");
        assertEquals("response", reloaded.getProfile("默认").getApiType(), "未设置方案默认 response");
        reloaded.getProfile("写作").setApiType("bogus");
        assertEquals("response", reloaded.getProfile("写作").getApiType(), "非法 API 类型回落 response");
        reloaded.removeProfile("默认");
        assertEquals(List.of("写作"), reloaded.getProfileNames(), "删除方案");
        reloaded.renameProfile("写作", "写作2");
        assertEquals(List.of("写作2"), reloaded.getProfileNames(), "重命名");
    }

    @Test
    void t02_legacyFlatKeyMigration() throws Exception {
        // 旧版平面键迁移：backendUrl/model/... 归入"默认"，全局字号保留
        Path legacySettings = file("legacy-settings.properties");
        store(properties("backendUrl", "http://old:8080/chat", "model", "legacy-model",
                "temperature", "0.5", "contentFontSize", "18"), legacySettings);
        AppSettings migrated = AppSettings.at(legacySettings);
        assertEquals(List.of("默认"), migrated.getProfileNames(), "迁移方案数");
        Profile legacy = migrated.getProfile(AppSettings.DEFAULT_PROFILE_NAME);
        assertEquals("legacy-model", legacy.getModel(), "迁移模型");
        assertEquals("http://old:8080/chat", legacy.getBackendUrl(), "迁移地址");
        assertEquals(0.5, legacy.getTemperature(), 1e-12, "迁移温度");
        assertEquals(18, migrated.getContentFontSize(), "全局字号保留");
        // models 键缺失（旧文件）时用 model 单值兜底成单元素列表
        assertEquals(List.of("legacy-model"), legacy.getModels(), "旧格式模型列表兜底");
        // apiType 键缺失（旧文件）时落默认 response
        assertEquals("response", legacy.getApiType(), "旧格式 API 类型默认");
    }

    @Test
    void t03_legacyModelsKeyFallback() throws Exception {
        // modelsMeta 缺键但 models 逗号键在（上一版格式）回落：名字照收，元数据落默认值
        Path legacyModels = file("legacy-models.properties");
        store(properties("profileNames", "默认",
                "profile.默认.model", "old-a", "profile.默认.models", "old-a,old-b"), legacyModels);
        AppSettings legacyMigrated = AppSettings.at(legacyModels);
        assertEquals(List.of("old-a", "old-b"), legacyMigrated.getProfile("默认").getModels(),
                "旧逗号键模型列表回落");
        assertFalse(legacyMigrated.findModelMeta("old-b").isImage(), "缺元数据条目参数落默认值");
    }

    @Test
    void t04_themePreference() {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        // 主题偏好：默认浅色、非法值忽略、跟随系统档、落盘往返
        assertEquals("light", testSettings.getUiTheme(), "主题默认浅色");
        testSettings.setUiTheme("深色");
        assertEquals("light", testSettings.getUiTheme(), "非法主题值忽略");
        testSettings.setUiTheme("system");
        assertEquals("system", testSettings.getUiTheme(), "跟随系统档落值");
        testSettings.setUiTheme("dark");
        testSettings.save();
        assertEquals("dark", AppSettings.at(settingsFile).getUiTheme(), "主题落盘往返");
        // resolveTheme：system 档探测后必落在两套主题之一（Windows 读注册表，失败按浅色）
        assertTrue(Set.of("light", "dark").contains(testSettings.resolvedUiTheme()), "系统主题解析收敛");
    }

    @Test
    void t05_logSettings() {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        // 默认：控制台开（info），文件关（追加写、不轮转、保留 5 份）
        assertTrue(testSettings.isLogConsoleEnabled(), "控制台默认开");
        assertEquals("info", testSettings.getLogConsoleLevel(), "控制台等级默认 info");
        assertNull(testSettings.getLogConsoleFormat(), "控制台格式默认用后端默认");
        assertFalse(testSettings.isLogFileEnabled(), "文件默认关");
        assertEquals("info", testSettings.getLogFileLevel(), "文件等级默认 info");
        assertNull(testSettings.getLogFilePath(), "文件路径默认用 loader 兜底");
        assertTrue(testSettings.isLogFileAppend(), "文件默认追加写");
        assertNull(testSettings.getLogFileMaxSize(), "默认不轮转");
        assertEquals(5, testSettings.getLogFileBackups(), "默认保留 5 份");
        // 等级非法值忽略；格式/路径空白归一成 null（=用默认）；保留份数钳制
        testSettings.setLogConsoleLevel("恐怖");
        assertEquals("info", testSettings.getLogConsoleLevel(), "非法日志等级忽略");
        testSettings.setLogConsoleLevel("debug");
        assertEquals("debug", testSettings.getLogConsoleLevel(), "合法等级落值");
        testSettings.setLogConsoleFormat("   ");
        assertNull(testSettings.getLogConsoleFormat(), "空白格式归一成 null");
        testSettings.setLogConsoleFormat(" {level}: {message} ");
        assertEquals("{level}: {message}", testSettings.getLogConsoleFormat(), "格式 strip 落值");
        testSettings.setLogFileBackups(0);
        assertEquals(1, testSettings.getLogFileBackups(), "保留份数下钳 1");
        testSettings.setLogFileBackups(5000);
        assertEquals(999, testSettings.getLogFileBackups(), "保留份数上钳 999");
        // 单文件上限：无单位/乱单位拒收，合法值（大小写单位均可）落值
        assertFalse(AppSettings.isValidLogFileSize("10"), "无单位不是合法上限");
        assertFalse(AppSettings.isValidLogFileSize("10tb"), "不认识的单位不是合法上限");
        assertTrue(AppSettings.isValidLogFileSize("10MB"), "单位大小写均可");
        testSettings.setLogFileMaxSize("10");
        assertNull(testSettings.getLogFileMaxSize(), "无单位拒收不设值");
        testSettings.setLogFileMaxSize(" 10mb ");
        assertEquals("10mb", testSettings.getLogFileMaxSize(), "合法上限 strip 落值");
        // 落盘往返：两块参数整体保存重读
        testSettings.setLogFileEnabled(true);
        testSettings.setLogFileLevel("warn");
        testSettings.setLogFilePath("logs/x.log");
        testSettings.setLogFileAppend(false);
        testSettings.setLogFileBackups(3);
        testSettings.setLogFileFormat("{date} {message}");
        testSettings.save();
        AppSettings reread = AppSettings.at(settingsFile);
        assertTrue(reread.isLogConsoleEnabled() && "debug".equals(reread.getLogConsoleLevel()),
                "控制台块落盘往返");
        assertEquals("{level}: {message}", reread.getLogConsoleFormat(), "控制台格式落盘往返");
        assertTrue(reread.isLogFileEnabled() && "warn".equals(reread.getLogFileLevel())
                && "logs/x.log".equals(reread.getLogFilePath()) && !reread.isLogFileAppend()
                && reread.getLogFileBackups() == 3 && "10mb".equals(reread.getLogFileMaxSize())
                && "{date} {message}".equals(reread.getLogFileFormat()), "文件块落盘往返");
    }

    @Test
    void t05b_legacyLogLevelKeyMigration() throws Exception {
        // 旧版全局 logLevel 键迁移成控制台等级（log.console.level 缺位时才用；非法旧值落默认）
        Path legacy = file("legacy-log.properties");
        store(properties("logLevel", "warn"), legacy);
        assertEquals("warn", AppSettings.at(legacy).getLogConsoleLevel(), "旧键迁移成控制台等级");
        store(properties("logLevel", "warn", "log.console.level", "debug"), legacy);
        assertEquals("debug", AppSettings.at(legacy).getLogConsoleLevel(), "新键优先于旧键");
        store(properties("logLevel", "恐怖"), legacy);
        assertEquals("info", AppSettings.at(legacy).getLogConsoleLevel(), "非法旧值落默认");
    }

    @Test
    void t06_trayPreference() {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        assertFalse(testSettings.isMinimizeToTray(), "托盘偏好默认关闭");
        testSettings.setMinimizeToTray(true);
        testSettings.save();
        assertTrue(AppSettings.at(settingsFile).isMinimizeToTray(), "托盘偏好落盘往返");
    }

    @Test
    void t07_approvalNotifyPreference() {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        // 审核通知三通道：默认全开、开关独立、落盘往返（工具审批到来时提醒哪些通道）
        assertTrue(testSettings.isApprovalSound() && testSettings.isApprovalSystemNotify()
                && testSettings.isApprovalBringToFront(), "审核通知默认全开");
        testSettings.setApprovalSound(false);
        testSettings.save();
        AppSettings notifyReread = AppSettings.at(settingsFile);
        assertTrue(!notifyReread.isApprovalSound() && notifyReread.isApprovalSystemNotify()
                && notifyReread.isApprovalBringToFront(), "审核通知偏好独立落盘往返");
    }

    @Test
    void t08_toolEnabledList() {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        // 工具勾选名单（前端存名单，后端消费 getEnabledTools 过滤声明/执行）：
        // 默认未配置=全启用；名单落盘往返；空名单=显式全不启用（与未配置语义区分）；未配置语义恢复
        assertTrue(testSettings.isToolEnabled("read"), "未配置时工具默认启用");
        testSettings.setEnabledTools(List.of("read", "bash"));
        testSettings.save();
        AppSettings toolsReread = AppSettings.at(settingsFile);
        assertTrue(toolsReread.getEnabledTools().equals(List.of("read", "bash"))
                && toolsReread.isToolEnabled("read") && !toolsReread.isToolEnabled("grep"), "工具名单落盘往返");
        testSettings.setEnabledTools(List.of());
        testSettings.save();
        assertFalse(AppSettings.at(settingsFile).isToolEnabled("read"), "空名单=显式全不启用");
        testSettings.setEnabledTools(null);
        testSettings.save();
        assertTrue(AppSettings.at(settingsFile).isToolEnabled("bash"), "未配置语义恢复全启用");
    }

    @Test
    void t09_activeProviderName() {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        // Provider 分组单选（全局落 AppSettings.activeProviderNames，per-apiType 不随方案）：
        // 默认未指定（null，后端回落默认）；落盘往返；各 API 类型独立；空白=清除指定（与 null 同语义）
        assertNull(testSettings.getActiveProviderName(Profile.API_RESPONSE), "未指定默认 null");
        testSettings.setActiveProviderName(Profile.API_MESSAGES, "messages-mine");
        testSettings.save();
        assertEquals("messages-mine", AppSettings.at(settingsFile).getActiveProviderName(Profile.API_MESSAGES),
                "provider 名落盘往返");
        assertNull(AppSettings.at(settingsFile).getActiveProviderName(Profile.API_RESPONSE),
                "未落盘的类型仍是未指定（各类型独立）");
        // 换类型再落一条，互不覆盖
        testSettings.setActiveProviderName(Profile.API_CHAT_COMPLETION, "chat-completion-mine");
        testSettings.save();
        AppSettings reread = AppSettings.at(settingsFile);
        assertEquals("chat-completion-mine", reread.getActiveProviderName(Profile.API_CHAT_COMPLETION),
                "chatcompletion 组的选择");
        assertEquals("messages-mine", reread.getActiveProviderName(Profile.API_MESSAGES),
                "messages 组的选择独立");
        // 空白清除该类型；strip 落值；未知类型丢弃
        reread.setActiveProviderName(Profile.API_MESSAGES, "   ");
        assertNull(reread.getActiveProviderName(Profile.API_MESSAGES), "空白清除指定");
        reread.setActiveProviderName(Profile.API_MESSAGES, " chat-completion-mine ");
        assertEquals("chat-completion-mine", reread.getActiveProviderName(Profile.API_MESSAGES), "strip 落值");
        reread.setActiveProviderName("unknown-api", "whatever");
        assertNull(reread.getActiveProviderName("unknown-api"), "未知 apiType 丢弃");
    }

    @Test
    void t10_bashFilePattern() throws Exception {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        // 默认未配置（null）：bash 工具只按超长阈值转文件，不拿字面量乱匹配
        assertNull(testSettings.getBashFilePattern(), "未配置默认 null");
        // 合法正则：设值 + 落盘往返
        testSettings.setBashFilePattern("(here-?doc|<<)");
        assertEquals("(here-?doc|<<)", testSettings.getBashFilePattern(), "合法正则设值");
        testSettings.save();
        assertEquals("(here-?doc|<<)", AppSettings.at(settingsFile).getBashFilePattern(), "落盘往返");
        // 非正常/不安全正则一律拒收（不设值，原值保持）：编译不过 / 嵌套量词炸弹 / 超长
        for (String bad : new String[]{"[unclosed", "(a+)+", "((a|b+)*)+", "a".repeat(257), "(?i)(x+)*"}) {
            testSettings.setBashFilePattern(bad);
        }
        assertEquals("(here-?doc|<<)", testSettings.getBashFilePattern(), "非法/不安全正则不设值");
        // 空白=清除配置，且清除后落盘不写键
        testSettings.setBashFilePattern("   ");
        assertNull(testSettings.getBashFilePattern(), "空白清除配置");
        testSettings.save();
        assertNull(AppSettings.at(settingsFile).getBashFilePattern(), "清除后落盘无该配置");
        // 手改配置文件塞进来的坏值 load 时不入库；合法值照常读
        store(properties("bashFilePattern", "(x+)+"), settingsFile);
        assertNull(AppSettings.at(settingsFile).getBashFilePattern(), "load 拒绝坏值");
        store(properties("bashFilePattern", "\\d+\\.sh"), settingsFile);
        assertEquals("\\d+\\.sh", AppSettings.at(settingsFile).getBashFilePattern(), "load 接受合法值");
        // 旧版无条件落盘的占位默认值 "something" 迁移为未配置（那不是用户的选择）
        store(properties("bashFilePattern", "something"), settingsFile);
        assertNull(AppSettings.at(settingsFile).getBashFilePattern(), "旧占位默认值迁移为未配置");
        // 校验器的"安全"边界：有界重复/普通分组量化的写法放行（误杀面别太宽）
        assertTrue(AppSettings.isValidBashFilePattern("(a|b)+c{2,3}"), "有界重复与普通量词分组放行");
        assertTrue(AppSettings.isValidBashFilePattern("[a+*.]{2,}\\\\w"), "字符类里的量词字符是字面量");
        assertFalse(AppSettings.isValidBashFilePattern("(?:ab|x+)+"), "非捕获分组嵌套量词同样拦下");
        assertFalse(AppSettings.isValidBashFilePattern(""), "空串不是合法配置");
        assertFalse(AppSettings.isValidBashFilePattern(null), "null 不是合法配置");
    }
}

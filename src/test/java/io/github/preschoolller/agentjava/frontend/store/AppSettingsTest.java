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
        // （上下文窗口纯数字，见 t12）
        ModelMeta richA = new ModelMeta("model-a");
        richA.setCtxWindow("256000");
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
        assertTrue("model-a".equals(savedA.getName()) && "256000".equals(savedA.getCtxWindow())
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
    void t10_activeContextHandler() {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        // 上下文页单选（全局落 AppSettings.activeContextHandler，不随方案）：
        // 默认不启用（null=消息原样发出）；设值 strip 落值；空白/null=不启用（清除）
        assertNull(testSettings.getActiveContextHandler(), "默认不启用");
        testSettings.setActiveContextHandler("  trim-handler  ");
        assertEquals("trim-handler", testSettings.getActiveContextHandler(), "strip 落值");
        testSettings.setActiveContextHandler("   ");
        assertNull(testSettings.getActiveContextHandler(), "空白=不启用（清除）");
        testSettings.setActiveContextHandler(null);
        assertNull(testSettings.getActiveContextHandler(), "null=不启用");
        // 落盘往返：缺键=不启用，有键=选择保留（名字是否在注册表里由后端消费时兜底）
        testSettings.setActiveContextHandler("storable-handler");
        testSettings.save();
        assertEquals("storable-handler", AppSettings.at(settingsFile).getActiveContextHandler(), "落盘往返");
        testSettings.setActiveContextHandler("");
        testSettings.save();
        assertNull(AppSettings.at(settingsFile).getActiveContextHandler(), "清除后落盘无该键");
    }

    @Test
    void t12_ctxWindowPureDigits() {
        // 上下文窗口只接受纯数字 token 数（消费方 Profile.getModelCtxWindow 是 Integer.parseInt
        // 直读，"256K" 之类只会解析成 0 还带错觉，宁可拒收）
        ModelMeta meta = new ModelMeta("m");
        meta.setCtxWindow("128000");
        assertEquals("128000", meta.getCtxWindow(), "纯数字设值");
        meta.setCtxWindow(" 2048 ");
        assertEquals("2048", meta.getCtxWindow(), "两侧空白 strip");
        meta.setCtxWindow("256K");
        assertEquals("2048", meta.getCtxWindow(), "带单位自由文本拒收保持原值（不静默洗掉旧落盘值）");
        meta.setCtxWindow("-1");
        assertEquals("2048", meta.getCtxWindow(), "负号拒收");
        meta.setCtxWindow("12 34");
        assertEquals("2048", meta.getCtxWindow(), "内嵌空格拒收");
        meta.setCtxWindow("   ");
        assertEquals("", meta.getCtxWindow(), "空白=清除（未设置）");
        meta.setCtxWindow(null);
        assertEquals("", meta.getCtxWindow(), "null=清除");
        // 校验器边界：空/null 是合法清除语义；含非数字不是合法配置值
        assertTrue(ModelMeta.isValidCtxWindow("0") && ModelMeta.isValidCtxWindow("99999999999"),
                "纯数字放行（上限由消费方解释）");
        assertFalse(ModelMeta.isValidCtxWindow(null) || ModelMeta.isValidCtxWindow("")
                || ModelMeta.isValidCtxWindow("1e5") || ModelMeta.isValidCtxWindow("２０４８"),
                "null/空串/科学计数/全角数字不是合法配置值");
    }

    @Test
    void t11_persistentToolInputRoundTrip() throws Exception {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        assertNull(testSettings.getPersistentToolInput("bash"), "未配置默认 null");
        // 前端交互后 set + save 成对：get 只读快照，set 不 save 读不见（契约如此，别单用 set）
        testSettings.setPersistentToolInput("bash", "CUSTOM:/usr/bin/bash");
        assertNull(testSettings.getPersistentToolInput("bash"), "未 save 前读不到（get 只读快照）");
        testSettings.save();
        assertEquals("CUSTOM:/usr/bin/bash", testSettings.getPersistentToolInput("bash"), "save 后读快照");
        // 用户报的丢字段根因回归：load 必须把 tool-properties.* 回填 toolProperties——
        // save() 是从模型重建整份文件，不回填的话启动后第一次防抖 save 就把键抹了
        AppSettings reloaded = AppSettings.at(settingsFile);
        assertEquals("CUSTOM:/usr/bin/bash", reloaded.getPersistentToolInput("bash"), "重启（重载）后读得见");
        reloaded.save();
        assertEquals("CUSTOM:/usr/bin/bash", reloaded.getPersistentToolInput("bash"), "重载后再 save 仍在");
        assertEquals("CUSTOM:/usr/bin/bash", AppSettings.at(settingsFile).getPersistentToolInput("bash"),
                "再落盘文件里仍在");
        // 泛化槽多工具互不串
        reloaded.setPersistentToolInput("read", "UTF-8");
        reloaded.save();
        assertEquals("CUSTOM:/usr/bin/bash", reloaded.getPersistentToolInput("bash"), "另一工具的键不受影响");
        assertEquals("UTF-8", reloaded.getPersistentToolInput("read"), "第二个工具写入独立");
    }
}

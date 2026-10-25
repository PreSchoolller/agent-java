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
        testSettings.getActiveProfile().setModel("test-model");
        assertEquals("test-model", testSettings.getModel(), "委托 getter 读当前方案");
        // 可选模型列表：设置→落盘→重读→默认项不在列表里时落到第一个
        testSettings.getActiveProfile().setModels(List.of("model-a", "model-b"));
        testSettings.getActiveProfile().setModel("model-b");
        assertEquals("model-b", testSettings.getActiveProfile().getModel(), "默认项在列表里保持");
        testSettings.getActiveProfile().setModels(List.of("model-c"));
        assertEquals("model-c", testSettings.getActiveProfile().getModel(), "默认项不在列表里落到第一个");
        testSettings.getActiveProfile().setModels(List.of("model-a", "model-b"));
        testSettings.getActiveProfile().setModel("model-b");
        // 模型目录元数据（计划外）：名字/上下文/最大输出/推理等级/输入能力整块落盘往返
        ModelMeta richA = new ModelMeta("model-a");
        richA.setCtxWindow("256K");
        richA.setMaxOutput("32K");
        richA.setReasoning("HIGH");
        richA.setText(true);
        ModelMeta richB = new ModelMeta("model-b");
        richB.setImage(true);
        testSettings.getActiveProfile().setModelMetas(List.of(richA, richB));
        testSettings.addProfile("写作", testSettings.getActiveProfile());
        testSettings.setActiveProfileName("写作");
        testSettings.getActiveProfile().setTemperature(0.3);
        testSettings.getActiveProfile().setApiType("chatcompletion");
        assertFalse(testSettings.addProfile("写作", testSettings.getActiveProfile()), "重名方案拒绝");
        testSettings.save();
        AppSettings reloaded = AppSettings.at(settingsFile);
        assertEquals(List.of("默认", "写作"), reloaded.getProfileNames(), "方案列表往返");
        assertEquals("写作", reloaded.getActiveProfileName(), "活动方案往返");
        assertEquals(0.3, reloaded.getActiveProfile().getTemperature(), 1e-12, "方案参数往返");
        assertEquals("model-b", reloaded.getProfile("默认").getModel(), "首个方案参数往返");
        assertEquals(List.of("model-a", "model-b"), reloaded.getProfile("写作").getModels(), "模型列表往返");
        assertEquals("model-b", reloaded.getProfile("写作").getModel(), "默认模型往返");
        ModelMeta savedA = reloaded.getProfile("写作").getModelMetas().get(0);
        assertTrue("model-a".equals(savedA.getName()) && "256K".equals(savedA.getCtxWindow())
                && "32K".equals(savedA.getMaxOutput()) && "HIGH".equals(savedA.getReasoning())
                && savedA.isText() && !savedA.isImage(), "模型元数据往返");
        assertTrue(reloaded.getProfile("写作").getModelMetas().get(1).isImage(), "模型图片能力往返");
        assertTrue("HIGH".equals(testSettings.getModelMeta("model-a").normalizedReasoning())
                && testSettings.getModelMeta("不存在") == null
                && testSettings.getModelMeta(null) == null, "模型元数据查找与非法推理等级回落");
        // API 类型（计划外，后端待支持）：写值往返；未设置的方案与旧文件缺键都是默认 response
        assertEquals("chatcompletion", reloaded.getProfile("写作").getApiType(), "API 类型往返");
        assertEquals("response", reloaded.getProfile("默认").getApiType(), "未设置方案默认 response");
        reloaded.getProfile("写作").setApiType("bogus");
        assertEquals("response", reloaded.getProfile("写作").getApiType(), "非法 API 类型回落 response");
        reloaded.removeProfile("默认");
        assertEquals("写作", reloaded.getActiveProfileName(), "删活动方案后兜底");
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
        assertEquals("legacy-model", migrated.getActiveProfile().getModel(), "迁移模型");
        assertEquals("http://old:8080/chat", migrated.getActiveProfile().getBackendUrl(), "迁移地址");
        assertEquals(0.5, migrated.getActiveProfile().getTemperature(), 1e-12, "迁移温度");
        assertEquals(18, migrated.getContentFontSize(), "全局字号保留");
        // models 键缺失（旧文件）时用 model 单值兜底成单元素列表
        assertEquals(List.of("legacy-model"), migrated.getActiveProfile().getModels(), "旧格式模型列表兜底");
        // apiType 键缺失（旧文件）时落默认 response
        assertEquals("response", migrated.getActiveProfile().getApiType(), "旧格式 API 类型默认");
    }

    @Test
    void t03_legacyModelsKeyFallback() throws Exception {
        // modelsMeta 缺键但 models 逗号键在（上一版格式）回落：名字照收，元数据落默认值
        Path legacyModels = file("legacy-models.properties");
        store(properties("profileNames", "默认", "activeProfile", "默认",
                "profile.默认.model", "old-a", "profile.默认.models", "old-a,old-b"), legacyModels);
        AppSettings legacyMigrated = AppSettings.at(legacyModels);
        assertEquals(List.of("old-a", "old-b"), legacyMigrated.getModels(), "旧逗号键模型列表回落");
        assertFalse(legacyMigrated.getModelMeta("old-b").isImage(), "缺元数据条目参数落默认值");
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
    void t05_logLevel() {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        // 日志等级：默认 info、非法值忽略、落盘往返（TinyLog 重启读一次，界面报价见 5 号块 tooltip）
        assertEquals("info", testSettings.getLogLevel(), "日志等级默认 info");
        testSettings.setLogLevel("恐怖");
        assertEquals("info", testSettings.getLogLevel(), "非法日志等级忽略");
        testSettings.setLogLevel("debug");
        testSettings.save();
        assertEquals("debug", AppSettings.at(settingsFile).getLogLevel(), "日志等级落盘往返");
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
    void t09_bashFilePattern() throws Exception {
        Path settingsFile = file("settings.properties");
        AppSettings testSettings = AppSettings.at(settingsFile);
        // 默认未配置（null）：bash 工具只按超长阈值转文件，不再拿 "something" 字面量乱匹配
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
        // 空白=清除配置，且清除后落盘不再写键
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

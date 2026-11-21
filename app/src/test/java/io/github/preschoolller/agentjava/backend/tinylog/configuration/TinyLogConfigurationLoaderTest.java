package io.github.preschoolller.agentjava.backend.tinylog.configuration;

import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TinyLogConfigurationLoader 映射测试：AppSettings.at(临时文件) 直喂 load(AppSettings)，
 * 不碰真实配置。覆盖：默认（仅控制台）、控制台+文件（file writer）、轮转（rolling file
 * writer + size 策略 + {count} 占位符补插）、格式透传、两路全关（level=off）。
 */
class TinyLogConfigurationLoaderTest {

    @TempDir
    Path dir;

    private AppSettings settings(String name) {
        return AppSettings.at(dir.resolve(name));
    }

    private final TinyLogConfigurationLoader loader = new TinyLogConfigurationLoader();

    @Test
    void defaults_consoleOnly() {
        // 默认设置：只有控制台路（info、默认格式），无 writer2，无全局 level 覆盖
        Properties properties = loader.load(settings("defaults.properties"));
        assertEquals("console", properties.getProperty("writer1"), "默认控制台 writer");
        assertEquals("info", properties.getProperty("writer1.level"), "默认等级 info");
        assertNull(properties.getProperty("writer1.format"), "默认格式不落 format 键（用 tinylog 默认）");
        assertNull(properties.getProperty("writer2"), "文件默认关，无第二个 writer");
        assertNull(properties.getProperty("level"), "有 writer 时不做全局 level 覆盖");
    }

    @Test
    void consoleAndPlainFile() {
        AppSettings s = settings("plain.properties");
        s.setLogConsoleLevel("debug");
        s.setLogConsoleFormat("{level}: {message}");
        s.setLogFileEnabled(true);
        s.setLogFileLevel("warn");
        s.setLogFilePath("logs/x.log");
        s.setLogFileAppend(false);
        s.setLogFileFormat("{date} {message}");
        Properties properties = loader.load(s);
        assertEquals("console", properties.getProperty("writer1"), "控制台路");
        assertEquals("debug", properties.getProperty("writer1.level"), "控制台等级透传");
        assertEquals("{level}: {message}", properties.getProperty("writer1.format"), "控制台格式透传");
        assertEquals("file", properties.getProperty("writer2"), "不轮转用 file writer");
        assertEquals("logs/x.log", properties.getProperty("writer2.file"), "文件路径透传");
        assertEquals("false", properties.getProperty("writer2.append"), "追加开关映射成 append");
        assertEquals("warn", properties.getProperty("writer2.level"), "文件等级透传");
        assertEquals("{date} {message}", properties.getProperty("writer2.format"), "文件格式透传");
        assertNull(properties.getProperty("writer2.policies"), "不轮转无 size 策略");
        assertNull(properties.getProperty("writer2.backups"), "不轮转无 backups");
    }

    @Test
    void rotatingFile() {
        AppSettings s = settings("rotating.properties");
        s.setLogFileEnabled(true);
        s.setLogFileMaxSize("10mb");
        s.setLogFileBackups(3);
        Properties properties = loader.load(s);
        assertEquals("console", properties.getProperty("writer1"), "控制台路默认开，占 writer1");
        assertEquals("rolling file", properties.getProperty("writer2"), "配了上限改用 rolling file writer");
        // 默认路径 ~/.agentjava/logs/agent.log 自动补 {count}：扩展名前插入，否则滚动各代互相覆盖
        assertTrue(properties.getProperty("writer2.file").endsWith("agent.{count}.log"),
                "默认文件名自动补 {count}，实际=" + properties.getProperty("writer2.file"));
        assertEquals("size: 10mb", properties.getProperty("writer2.policies"), "上限映射成 size 策略");
        assertEquals("3", properties.getProperty("writer2.backups"), "保留份数映射成 backups");
        assertNull(properties.getProperty("writer2.append"), "rolling writer 无 append 概念");
        // 文件路关闭后只剩控制台：不残留 rolling 配置
        s.setLogFileEnabled(false);
        Properties consoleOnly = loader.load(s);
        assertEquals("console", consoleOnly.getProperty("writer1"));
        assertNull(consoleOnly.getProperty("writer2"));
    }

    @Test
    void bothDisabled_silent() {
        AppSettings s = settings("silent.properties");
        s.setLogConsoleEnabled(false);
        Properties properties = loader.load(s);
        assertEquals("off", properties.getProperty("level"), "两路全关整体静默");
        assertNull(properties.getProperty("writer1"), "全关时不配任何 writer");
    }

    @Test
    void withCountPlaceholder_edges() {
        assertEquals("logs/agent.{count}.log", TinyLogConfigurationLoader.withCountPlaceholder("logs/agent.log"),
                "扩展名前插入");
        assertEquals("logs/agent.{count}", TinyLogConfigurationLoader.withCountPlaceholder("logs/agent"),
                "无扩展名追加尾部");
        assertEquals("C:/tmp/a.{count}.log", TinyLogConfigurationLoader.withCountPlaceholder("C:/tmp/a.log"),
                "绝对路径同规则");
        assertEquals(".hidden.{count}", TinyLogConfigurationLoader.withCountPlaceholder(".hidden"),
                "隐藏文件（首字符点）当无扩展名追加");
        assertEquals("a.{count}.log", TinyLogConfigurationLoader.withCountPlaceholder("a.{count}.log"),
                "已含 {count} 原样返回");
        assertEquals("a.{count}", TinyLogConfigurationLoader.withCountPlaceholder("a.{count}"),
                "尾部 {count} 也算已含");
    }
}

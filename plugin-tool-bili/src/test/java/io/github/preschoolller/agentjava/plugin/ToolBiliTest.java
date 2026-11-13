package io.github.preschoolller.agentjava.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolBiliTest {

    // ---- 白名单 ----

    @Test
    void readAndWriteWhitelistsAreDisjoint() {
        Set<String> overlap = new java.util.HashSet<>(ToolBiliRead.READ_COMMANDS.keySet());
        overlap.retainAll(ToolBiliWrite.WRITE_COMMANDS.keySet());
        assertTrue(overlap.isEmpty(), "读写白名单不允许重叠: " + overlap);
    }

    @Test
    void toolNamesMatchSchemaContract() {
        assertEquals("bili", new ToolBiliRead().name());
        assertEquals("bili_write", new ToolBiliWrite().name());
        // 设置面板持久化键 = 工具 name()，BiliLocator 两个键必须能对上
        assertEquals(BiliLocator.PERSISTED_KEY_READ, new ToolBiliRead().name());
        assertEquals(BiliLocator.PERSISTED_KEY_WRITE, new ToolBiliWrite().name());
    }

    @Test
    void readToolIsReadOnlyWriteToolIsNot() {
        assertTrue(new ToolBiliRead().readOnly());
        assertFalse(new ToolBiliWrite().readOnly());
    }

    @Test
    void schemasExposeCommandEnum() {
        List<String> readEnum = enumOf(new ToolBiliRead().schema(), "command");
        assertTrue(readEnum.contains("video"));
        assertTrue(readEnum.contains("debrief"));
        assertFalse(readEnum.contains("comment"));

        List<String> writeEnum = enumOf(new ToolBiliWrite().schema(), "command");
        assertTrue(writeEnum.contains("comment"));
        assertTrue(writeEnum.contains("dm"));
        assertFalse(writeEnum.contains("video"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumOf(Map<String, Object> schema, String prop) {
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> command = (Map<String, Object>) properties.get(prop);
        return (List<String>) command.get("enum");
    }

    // ---- 写参数净化 ----

    @Test
    void sanitizeStripsModelSuppliedFlags() {
        // 模型想自带 --go 绕过确认，剥掉
        List<String> out = ToolBiliWrite.sanitizeArgs(
                "comment", List.of("--go", "BV123", "--dry", "内容"), false);
        assertEquals(List.of("comment", "BV123", "内容", "--dry"), out);
    }

    @Test
    void sanitizeGoTrueAppendsGo() {
        List<String> out = ToolBiliWrite.sanitizeArgs("dm", List.of("--msg", "你好"), true);
        assertEquals(List.of("dm", "--msg", "你好", "--go"), out);
    }

    @Test
    void sanitizeDefaultDryOnlyForNeedsFlagCommands() {
        // 需要显式标志的命令：缺省补 --dry
        assertEquals(List.of("fav", "BV1", "--dry"),
                ToolBiliWrite.sanitizeArgs("fav", List.of("BV1"), false));
        // 不需要标志的命令（reply-draft/notify）：原样透传
        assertEquals(List.of("reply-draft", "add", "x"),
                ToolBiliWrite.sanitizeArgs("reply-draft", List.of("add", "x"), false));
        assertEquals(List.of("notify", "hello"),
                ToolBiliWrite.sanitizeArgs("notify", List.of("hello"), false));
    }

    @Test
    void sanitizeKeepsDryIfAlreadyPresent() {
        assertEquals(List.of("dynamic", "post", "--dry"),
                ToolBiliWrite.sanitizeArgs("dynamic", List.of("post", "--dry"), false));
    }

    // ---- 超时 ----

    @Test
    void timeoutClamp() {
        assertEquals(120, BiliRunner.clampTimeout(null));
        assertEquals(120, BiliRunner.clampTimeout(0));
        assertEquals(45, BiliRunner.clampTimeout(45));
        assertEquals(900, BiliRunner.clampTimeout(99999));
    }
}

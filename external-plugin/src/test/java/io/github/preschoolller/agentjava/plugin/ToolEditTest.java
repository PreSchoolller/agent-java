package io.github.preschoolller.agentjava.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolEdit 行为测试：四种模式 + 边界场景（重叠行、空文件、UTF-8、未命中）。
 * 全部走真实文件（@TempDir），验证的是文件最终内容，不是工具返回文本。
 */
class ToolEditTest {

    private final ToolEdit tool = new ToolEdit();

    @TempDir
    Path ws;

    // ---------------- DELETE ----------------

    @Test
    @DisplayName("DELETE：命中在文件中部，只删命中行（防回归：起始偏移恒为 0 会砍掉文件头）")
    void deleteMidFile() throws IOException {
        assertEquals("l1\nl4\n", edit("t.txt", "l1\nl2\nl3\nl4\n",
                "{\"path\":\"t.txt\",\"old_string\":\"l2\\nl3\",\"new_string\":\"\",\"mode\":1}"));
    }

    @Test
    @DisplayName("DELETE：重叠行 A A B 删 A\\nB，应留 A（首行重复时起点要前移）")
    void deleteOverlappingLines() throws IOException {
        assertEquals("A\n", edit("t.txt", "A\nA\nB\n",
                "{\"path\":\"t.txt\",\"old_string\":\"A\\nB\",\"new_string\":\"\",\"mode\":1}"));
    }

    // ---------------- REPLACE ----------------

    @Test
    @DisplayName("REPLACE：多行块替换，替换文本自动补行尾换行")
    void replaceMultiLineBlock() throws IOException {
        assertEquals("keep1\nnew1\nnew2\nkeep2\n", edit("t.txt", "keep1\nold1\nold2\nkeep2\n",
                "{\"path\":\"t.txt\",\"old_string\":\"old1\\nold2\",\"new_string\":\"new1\\nnew2\",\"mode\":2}"));
    }

    @Test
    @DisplayName("REPLACE：空新文本等于干净删除，不残留空行")
    void replaceEmptyNewString() throws IOException {
        assertEquals("a\nc\n", edit("t.txt", "a\nb\nc\n",
                "{\"path\":\"t.txt\",\"old_string\":\"b\",\"new_string\":\"\",\"mode\":2}"));
    }

    // ---------------- REPLACE_ALL ----------------

    @Test
    @DisplayName("REPLACE_ALL：单行两处命中，gap 组装不重复不遗漏")
    void replaceAllSingleLineTwice() throws IOException {
        assertEquals("x\nbar\nmid\nbar\ny\n", edit("t.txt", "x\nfoo\nmid\nfoo\ny\n",
                "{\"path\":\"t.txt\",\"old_string\":\"foo\",\"new_string\":\"bar\",\"mode\":3}"));
    }

    @Test
    @DisplayName("REPLACE_ALL：多行块两处命中")
    void replaceAllMultiLineBlocks() throws IOException {
        assertEquals("s\nZ\nm1\nZ\ne\n", edit("t.txt", "s\nA\nB\nm1\nA\nB\ne\n",
                "{\"path\":\"t.txt\",\"old_string\":\"A\\nB\",\"new_string\":\"Z\",\"mode\":3}"));
    }

    // ---------------- APPEND ----------------

    @Test
    @DisplayName("APPEND：空文件直接写入，不补换行也不抛异常（防回归：seek(-1)）")
    void appendToEmptyFile() throws IOException {
        assertEquals("hello", edit("t.txt", "",
                "{\"path\":\"t.txt\",\"old_string\":\"\",\"new_string\":\"hello\",\"mode\":0}"));
    }

    @Test
    @DisplayName("APPEND：末行无换行先补换行再追加")
    void appendWithoutTrailingNewline() throws IOException {
        assertEquals("abc\nx", edit("t.txt", "abc",
                "{\"path\":\"t.txt\",\"old_string\":\"\",\"new_string\":\"x\",\"mode\":0}"));
    }

    @Test
    @DisplayName("APPEND：末行已有换行则不额外补空行")
    void appendWithTrailingNewline() throws IOException {
        assertEquals("abc\nx", edit("t.txt", "abc\n",
                "{\"path\":\"t.txt\",\"old_string\":\"\",\"new_string\":\"x\",\"mode\":0}"));
    }

    // ---------------- 其他 ----------------

    @Test
    @DisplayName("UTF-8 中文：行匹配与字节偏移对多字节字符正确")
    void utf8Content() throws IOException {
        assertEquals("第一行\n第三行\n", edit("t.txt", "第一行\n中文旧文本\n第三行\n",
                "{\"path\":\"t.txt\",\"old_string\":\"中文旧文本\",\"new_string\":\"\",\"mode\":1}"));
    }

    @Test
    @DisplayName("未命中：返回错误文本，文件原样")
    void notFound() throws IOException {
        Path f = Files.writeString(ws.resolve("t.txt"), "aaa\n");
        String result = tool.execute(
                "{\"path\":\"t.txt\",\"old_string\":\"zzz\",\"new_string\":\"\",\"mode\":1}", ws.toString(), null);
        assertTrue(result.startsWith("(edit error"), "should fail, got: " + result);
        assertEquals("aaa\n", Files.readString(f));
    }

    /** 写入初始内容、执行编辑（断言成功）、读回文件最终内容 */
    private String edit(String file, String initial, String json) throws IOException {
        Path f = Files.writeString(ws.resolve(file), initial);
        String result = tool.execute(json, ws.toString(), null);
        assertTrue(result.contains("success"), "edit should succeed, got: " + result);
        return Files.readString(f);
    }
}

package io.github.preschoolller.agentjava.frontend.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Markdown 解析单元测试（原 foragent/tools/Shot.java 的 MD_PARSE 段平移，逐条保留原断言文案）。 */
class MarkdownParseTest {

    private static List<MarkdownRenderer.Block> parseSample() {
        return MarkdownRenderer.parse(SampleMd.SAMPLE_MD);
    }

    @Test
    void blockCounts() {
        List<MarkdownRenderer.Block> md = parseSample();
        assertEquals(1, md.stream().filter(b -> b instanceof MarkdownRenderer.Heading).count(), "标题数");
        assertEquals(4, md.stream().filter(b -> b instanceof MarkdownRenderer.Paragraph).count(), "段落数");
        assertEquals(6, md.stream().filter(b -> b instanceof MarkdownRenderer.ListItem).count(), "列表项数");
        assertEquals(2, md.stream().filter(b -> b instanceof MarkdownRenderer.CodeBlock).count(), "代码块数");
    }

    @Test
    void diffBlockPreserved() {
        // diff 块：内容原样进 CodeBlock（渲染层按行着色），增行删行都在
        MarkdownRenderer.CodeBlock diffBlock = parseSample().stream()
                .filter(b -> b instanceof MarkdownRenderer.CodeBlock c && "diff".equals(c.language()))
                .map(b -> (MarkdownRenderer.CodeBlock) b).findFirst().orElseThrow();
        assertEquals(8, diffBlock.code().lines().count(), "diff 行数");
        assertTrue(diffBlock.code().contains("-    private static final int SIZE = 10;")
                && diffBlock.code().contains("+    private static final int SIZE = 20;"), "diff 增删行原样");
    }

    @Test
    void quoteAndRuleCounts() {
        List<MarkdownRenderer.Block> md = parseSample();
        assertEquals(1, md.stream().filter(b -> b instanceof MarkdownRenderer.Quote).count(), "引用数");
        assertEquals(1, md.stream().filter(b -> b instanceof MarkdownRenderer.Rule).count(), "分隔线数");
    }

    @Test
    void tableCells() {
        // 表格（P1-5）：分隔行识别、单元格内容、转义竖线、表头夹在段落里也能接管
        List<MarkdownRenderer.TableBlock> tableOnly = parseSample().stream()
                .filter(b -> b instanceof MarkdownRenderer.TableBlock)
                .map(b -> (MarkdownRenderer.TableBlock) b).toList();
        assertEquals(1, tableOnly.size(), "表格块数");
        MarkdownRenderer.TableBlock table = tableOnly.get(0);
        assertEquals(3, table.rows().size(), "表格行数");
        assertEquals("工具", table.rows().get(0).get(0).get(0).text(), "表头首格");
        assertEquals("读取文本文件内容", table.rows().get(1).get(2).get(0).text(), "体行说明格");
        assertEquals("write", table.rows().get(2).get(0).get(0).text(), "体行工具名格");
    }

    @Test
    void headingAloneDoesNotMakeTable() {
        List<MarkdownRenderer.Block> notTable = MarkdownRenderer.parse("没有分隔行不算表格\n| a | b |\n| --- | --- |");
        assertInstanceOf(MarkdownRenderer.Paragraph.class, notTable.get(0), "无分隔行的孤立段落不成表");
        assertInstanceOf(MarkdownRenderer.TableBlock.class, notTable.get(1), "段落后的表头能接管成表");
    }

    @Test
    void escapedPipe() {
        List<MarkdownRenderer.Block> escaped = MarkdownRenderer.parse("| a \\| b | c |\n| --- | --- |");
        MarkdownRenderer.TableBlock table = assertInstanceOf(MarkdownRenderer.TableBlock.class, escaped.get(0));
        assertEquals("a | b", table.rows().get(0).get(0).get(0).text(), "转义竖线");
    }
}

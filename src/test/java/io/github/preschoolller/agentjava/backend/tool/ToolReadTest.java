package io.github.preschoolller.agentjava.backend.tool;

import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.provider.common.ToolApprovalGate;
import io.github.preschoolller.agentjava.backend.tool.interfaces.NeedAdditionalApproval;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolReadTest {

    @Test
    void getInstance() {
        assertNotNull(ToolRead.getInstance());
    }

    @Test
    void name() {
        assertEquals("read", ToolRead.getInstance().name());
    }

    @Test
    void description() {
        assertEquals("读取文本文件内容（相对路径按当前工作区解析），非必要不要超出工作区，若是超出工作区则视情况需要用户审批，较为耗时。单行默认超过" + 500 + "截断。",
                ToolRead.getInstance().description());
    }

    @Test
    void readOnly() {
        assertTrue(ToolRead.getInstance().readOnly());
    }

    @Test
    void schema() {
        assertEquals(Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "文件路径，相对路径以当前工作区为基准"),
                        "offset", Map.of("type", "integer", "description", "起始行数，默认第一行(offset = 1)开始"),
                        "no_truncate", Map.of("type", "boolean", "description", "单行超长默认截断，传递true原样返回"),
                        "limit", Map.of("type", "integer", "description", "总限制行数，默认2000，最大2000")
                ),
                "required", List.of("path")), ToolRead.getInstance().schema());
    }

    @Test
    void supportsAdditionalApproval() {
        assertTrue(ToolRead.getInstance().supportsAdditionalApproval());
    }

    @Test
    void testExecute_Failed_WrongSchema() {
        String arguments = "{\"path\": \"src/test/resources/tool_read.txt: 2}";
        String workSpace = ".";
        NeedAdditionalApproval approvalGate = noMore -> true;
        String result = ToolRead.getInstance().execute(arguments, workSpace, approvalGate);
        // TODO: wrong schema 好像会直接抛异常，走不到 jsonNode == null ？？？
        assertTrue(result.startsWith("json parse error:"));
    }

    @Test
    void testExecute_Failed_WrongPath() {
        String arguments = "";
        String workSpace = ".";
        NeedAdditionalApproval approvalGate = noMore -> true;
        String result = ToolRead.getInstance().execute(arguments, workSpace, approvalGate);
        assertEquals("json parse error: wrong path", result);
    }

    @Test
    void testExecute_Failed_NoFile() {
        String randomFile = UUID.randomUUID().toString();
        String arguments = "{\"path\": \"src/test/resources/" + randomFile + ".txt\", \"offset\": 3, \"limit\": 2}";
        String workSpace = ".";
        NeedAdditionalApproval approvalGate = noMore -> true;
        String result = ToolRead.getInstance().execute(arguments, workSpace, approvalGate);
        assertTrue(result.startsWith("file read error: "));
    }

    @Test
    void testExecute_Success() {
        String arguments = "{\"path\": \"src/test/resources/tool_read.txt\", \"offset\": 3, \"limit\": 2}";
        String workSpace = ".";
        NeedAdditionalApproval approvalGate = noMore -> true;
        String result = ToolRead.getInstance().execute(arguments, workSpace, approvalGate);
        // TODO : 写测试发现每一行结尾都固定加了 \n ，测试写完改一下，输出末行不加
        assertEquals("3:这是第三三三行；\n4:这是第四四四四行；\n", result);
    }

    @Test
    void testExecute_Success_Truncate() {
        String arguments = "{\"path\": \"src/test/resources/tool_read.txt\", \"offset\": 7, \"limit\": 1}";
        String workSpace = ".";
        NeedAdditionalApproval approvalGate = noMore -> true;
        String result = ToolRead.getInstance().execute(arguments, workSpace, approvalGate);
        assertEquals("7:01234567890123456789012345678901234567890123456789" +
                "01234567890123456789012345678901234567890123456789" +
                "01234567890123456789012345678901234567890123456789" +
                "01234567890123456789012345678901234567890123456789" +
                "01234567890123456789012345678901234567890123456789" +
                "01234567890123456789012345678901234567890123456789" +
                "01234567890123456789012345678901234567890123456789" +
                "01234567890123456789012345678901234567890123456789" +
                "01234567890123456789012345678901234567890123456789" +
                "0123456789012345678901234567890123456789---中文结束是五百...truncated 500/600\n", result);
    }

    @Test
    void testExecute_Success_OutSideWorkspace_AndNoMoreLines() throws IOException {
        Path tempFile = Files.createTempFile(Constants.TEMP_DIR, "tool_read_test", "txt");
        String str = "hello test!";
        Files.writeString(tempFile, str, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        String path = escapeWindowsSlash(tempFile);
        String arguments = "{\"path\": \"" + path + "\", \"offset\": 1, \"limit\": 100}";
        String workSpace = ".";
        System.out.println(arguments);
        NeedAdditionalApproval approvalGate = noMore -> true;
        String result = ToolRead.getInstance().execute(arguments, workSpace, approvalGate);
        assertEquals("1:" + str + "\nno more lines since 2", result);
    }

    private static String escapeWindowsSlash(Path tempFile) {
        return tempFile.toAbsolutePath().toString().replaceAll("\\\\", "\\\\\\\\");
    }

    @Test
    void testExecute_Failed_OutSideWorkspace_Denied() throws IOException {
        Path tempFile = Files.createTempFile(Constants.TEMP_DIR, "tool_read_test", "txt");
        String str = "hello test!";
        Files.writeString(tempFile, str, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        String path = escapeWindowsSlash(tempFile);
        String arguments = "{\"path\": \"" + path + "\", \"offset\": 1, \"limit\": 100}";
        String workSpace = ".";
        System.out.println(arguments);
        NeedAdditionalApproval approvalGate = noMore -> false;
        String result = ToolRead.getInstance().execute(arguments, workSpace, approvalGate);
        assertEquals(ToolApprovalGate.DENIED_RESULT, result);
    }
}
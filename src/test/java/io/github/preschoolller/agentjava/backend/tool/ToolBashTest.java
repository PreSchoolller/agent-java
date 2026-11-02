package io.github.preschoolller.agentjava.backend.tool;

import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ToolBashTest {

    @Test
    void getInstance() {
        assertNotNull(ToolBash.getInstance());
    }

    @Test
    void name() {
        assertEquals("bash", ToolBash.getInstance().name());
    }

    @Test
    void description() {
        assertEquals("""
                在工作区执行bash命令。
                如果agent运行环境（由工作区路径判断）非windows，bash为原生实现。
                如果agent运行环境为Windows，则bash实现查找顺序(wsl bash -> git bash)。
                由于windows上两种种bash实现的路径表示不一样，可先pwd判断格式再执行绝对路径命令书写。
                """, ToolBash.getInstance().description());
    }

    @Test
    void readOnly() {
        assertFalse(ToolBash.getInstance().readOnly());
    }

    @Test
    void supportsBackGroundTask() {
        assertTrue(ToolBash.getInstance().supportsBackGroundTask());
    }

    @Test
    void schema() {
        assertEquals(Map.of(
                "type", "object",
                "properties", Map.of("command", Map.of("type", "string", "description", "bash命令"),
                        "background", Map.of("type", "boolean", "description", "是否要作为后台进程运行，当次调用仅返回提交成功/失败，后台任务完成后结果会插入额外信息中")
                ),
                "required", List.of("command")
        ), ToolBash.getInstance().schema());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void testBuildTmpFilePathWhenLinux() {
        String tmpFileName = UUID.randomUUID().toString();
        Path tmpFile = Constants.TEMP_DIR.resolve(tmpFileName);
        String path = ToolBash.buildTmpFilePath(tmpFile, ToolBash.BashHead.L);
        assertEquals(tmpFile.toAbsolutePath().toString(), path);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testBuildTmpFilePathWhenWindowsWsl() {
        String tmpFileName = "C:\\aaa\\bbb\\ccc";
        Path tmpFile = Path.of(tmpFileName);
        String path = ToolBash.buildTmpFilePath(tmpFile, ToolBash.BashHead.WSL);
        assertEquals("/mnt/c/aaa/bbb/ccc", path);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testBuildTmpFilePathWhenWindowsGit() {
        String tmpFileName = "C:\\aaa\\bbb\\ccc";
        Path tmpFile = Path.of(tmpFileName);
        String path = ToolBash.buildTmpFilePath(tmpFile, ToolBash.BashHead.GIT);
        assertEquals("/c/aaa/bbb/ccc", path);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testAssembleProcessBuilderWhenShortOnWindowsWsl() {
        String command = "echo hello";
        String workSpace = "workspace";
        Path tempPath = Path.of("C:\\aaa\\bbb\\ccc");
        List<String> expectedCommand = new ArrayList<>(ToolBash.BashHead.WSL.head);
        String base64 = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8));
        expectedCommand.add("-c");
        expectedCommand.add("echo " + base64 + " | base64 -d | bash");
        ProcessBuilder processBuilder = ToolBash.assembleProcessBuilder(command, workSpace, ToolBash.BashHead.WSL, tempPath);
        assertEquals(expectedCommand, processBuilder.command());
        assertEquals(new File(workSpace), processBuilder.directory());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testAssembleProcessBuilderWhenShortButMatchPatternOnWindowsWsl() {
        String command = "echo hello";
        String workSpace = "workspace";
        Path tempPath = Path.of("C:\\Windows\\Temp");
        List<String> expectedCommand = new ArrayList<>(ToolBash.BashHead.WSL.head);
        ProcessBuilder processBuilder;
        try (MockedStatic<AppSettings> mocked = Mockito.mockStatic(AppSettings.class)) {
            AppSettings mock = Mockito.mock(AppSettings.class);
            mocked.when(AppSettings::get).thenReturn(mock);
            Mockito.when(mock.getBashFilePattern()).thenReturn("hello");
            processBuilder = ToolBash.assembleProcessBuilder(command, workSpace, ToolBash.BashHead.WSL, tempPath);
        }
        List<String> resultCommand = processBuilder.command();
        assertEquals(expectedCommand.get(0), resultCommand.get(0));
        assertEquals(expectedCommand.get(1), resultCommand.get(1));
        assertTrue(resultCommand.get(2).startsWith("/mnt/c/Windows/Temp/workspace/"));
        assertEquals(new File(workSpace), processBuilder.directory());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void testAssembleProcessBuilderWhenShortButMatchPatternOnWindowsGit() {
        String command = "echo hello";
        String workSpace = "workspace";
        Path tempPath = Path.of("C:\\Windows\\Temp");
        List<String> expectedCommand = new ArrayList<>(ToolBash.BashHead.GIT.head);
        ProcessBuilder processBuilder;
        try (MockedStatic<AppSettings> mocked = Mockito.mockStatic(AppSettings.class)) {
            AppSettings mock = Mockito.mock(AppSettings.class);
            mocked.when(AppSettings::get).thenReturn(mock);
            Mockito.when(mock.getBashFilePattern()).thenReturn("hello");
            processBuilder = ToolBash.assembleProcessBuilder(command, workSpace, ToolBash.BashHead.GIT, tempPath);
        }
        List<String> resultCommand = processBuilder.command();
        assertEquals(expectedCommand.get(0), resultCommand.get(0));
        assertTrue(resultCommand.get(1).startsWith("/c/Windows/Temp/workspace/"));
        assertEquals(new File(workSpace), processBuilder.directory());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void testAssembleProcessBuilderWhenShortButMatchPatternOnLinux() {
        String command = "echo hello";
        String workSpace = "workspace";
        Path tempPath = Path.of("/tmp");
        List<String> expectedCommand = new ArrayList<>(ToolBash.BashHead.L.head);
        ProcessBuilder processBuilder;
        try (MockedStatic<AppSettings> mocked = Mockito.mockStatic(AppSettings.class)) {
            AppSettings mock = Mockito.mock(AppSettings.class);
            mocked.when(AppSettings::get).thenReturn(mock);
            Mockito.when(mock.getBashFilePattern()).thenReturn("hello");
            processBuilder = ToolBash.assembleProcessBuilder(command, workSpace, ToolBash.BashHead.L, tempPath);
        }
        List<String> resultCommand = processBuilder.command();
        assertEquals(expectedCommand.get(0), resultCommand.get(0));
        assertTrue(resultCommand.get(1).startsWith("/tmp/workspace/"));
        assertEquals(new File(workSpace), processBuilder.directory());
    }

    private static void setBashHead(ToolBash.BashHead bashHead) {
        try {
            Field bashHeadField = ToolBash.class.getDeclaredField("bashHead");
            bashHeadField.setAccessible(true);
            bashHeadField.set(null, bashHead);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setValid(boolean valid) {
        try {
            Field validField = ToolBash.class.getDeclaredField("valid");
            validField.setAccessible(true);
            validField.set(null, valid);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void beforeEach() {
        setBashHead(null);
        setValid(true);
    }

    @Test
    void testExecuteWhenInvalid() {
        setBashHead(ToolBash.BashHead.WSL);
        setValid(false);
        String command = "echo hello";
        String arguments = "{\"command\": " + command + "}";
        String workSpace = "workspace";
        String result = ToolBash.getInstance().execute(arguments, workSpace, "ignore", System.out::println);
        assertEquals("tool error: no valid bash command found in system", result);
    }

    @Test
    void testExecuteWhenNullArguments() {
        setBashHead(ToolBash.BashHead.WSL);
        String arguments = null;
        String workSpace = "workspace";
        String result = ToolBash.getInstance().execute(arguments, workSpace, "ignore", System.out::println);
        assertEquals("agent app error: unknown", result);
    }

    @Test
    void testExecuteWhenWrongJson() {
        setBashHead(ToolBash.BashHead.WSL);
        String arguments = "{\"command\":";
        String workSpace = "workspace";
        String result = ToolBash.getInstance().execute(arguments, workSpace, "ignore", System.out::println);
        assertTrue(result.startsWith("json parse error: "));
    }

    @Test
    void testExecuteWhenLiteralNullArguments() {
        setBashHead(ToolBash.BashHead.WSL);
        String arguments = "null";
        String workSpace = "workspace";
        String result = ToolBash.getInstance().execute(arguments, workSpace, "ignore", System.out::println);
        assertEquals("json parse error: wrong schema", result);
    }

    @Test
    void testExecuteWhenEmptyArguments() {
        setBashHead(ToolBash.BashHead.WSL);
        String arguments = "";
        String workSpace = "workspace";
        String result = ToolBash.getInstance().execute(arguments, workSpace, "ignore", System.out::println);
        assertEquals("json parse error: wrong command", result);
    }
}
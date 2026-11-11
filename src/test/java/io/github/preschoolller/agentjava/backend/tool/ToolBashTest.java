package io.github.preschoolller.agentjava.backend.tool;

import io.github.preschoolller.agentjava.backend.constant.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

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
                用户也可以在应用设置里指定自定义bash可执行程序，配置了则优先使用。
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
        setPatternField(Pattern.compile("hello"));
        ProcessBuilder processBuilder = ToolBash.assembleProcessBuilder(command, workSpace, ToolBash.BashHead.WSL, tempPath);
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
        setPatternField(Pattern.compile("hello"));
        ProcessBuilder processBuilder = ToolBash.assembleProcessBuilder(command, workSpace, ToolBash.BashHead.GIT, tempPath);
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
        setPatternField(Pattern.compile("hello"));
        ProcessBuilder processBuilder = ToolBash.assembleProcessBuilder(command, workSpace, ToolBash.BashHead.L, tempPath);
        List<String> resultCommand = processBuilder.command();
        assertEquals(expectedCommand.get(0), resultCommand.get(0));
        assertTrue(resultCommand.get(1).startsWith("/tmp/workspace/"));
        assertEquals(new File(workSpace), processBuilder.directory());
    }

    /** 转文件正则经 doRegex（uiInteractive 的 regex= 值）设置；测试用反射直接注入/读取静态字段 */
    private static void setPatternField(Pattern pattern) {
        try {
            Field patternField = ToolBash.class.getDeclaredField("toFilePattern");
            patternField.setAccessible(true);
            patternField.set(null, pattern);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Pattern getPatternField() {
        try {
            Field patternField = ToolBash.class.getDeclaredField("toFilePattern");
            patternField.setAccessible(true);
            return (Pattern) patternField.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** 反射调私有 doRegex：uiInteractive 的 regex= 分支，验证配置语义与安全拦截 */
    private static String invokeDoRegex(String regex) {
        try {
            Method method = ToolBash.class.getDeclaredMethod("doRegex", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, regex);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testDoRegexSetAndClear() {
        assertEquals("正则配置成功", invokeDoRegex("(here-?doc|<<)"));
        assertNotNull(getPatternField());
        assertTrue(ToolBash.needPutInFile("echo (here-?doc|<<)"));
        // null = 键未出现 = 不动
        assertEquals("当前正则：(here-?doc|<<)", invokeDoRegex(null));
        assertNotNull(getPatternField());
        // 空串 = 键在值空 = 显式清除
        assertEquals("已重设转文件执行正则为空", invokeDoRegex(""));
        assertNull(getPatternField());
        assertFalse(ToolBash.needPutInFile("echo (here-?doc|<<)"));
    }

    @Test
    void testDoRegexRejectsUnsafe() {
        // 编译不过
        assertEquals("正则编译失败，请检查正则表达式", invokeDoRegex("[unclosed"));
        // 回溯炸弹（嵌套无限量词）：普通分组与非捕获分组都拦
        assertEquals("正则编译失败，请检查是否有嵌套无限量词", invokeDoRegex("(a+)+"));
        assertEquals("正则编译失败，请检查是否有嵌套无限量词", invokeDoRegex("(?:ab|x+)+"));
        // 超长
        assertEquals("正则为空或超长256故未进行配置", invokeDoRegex("a".repeat(257)));
        // 有界重复与普通量词分组放行（误杀面别太宽）
        assertEquals("正则配置成功", invokeDoRegex("(a|b)+c{2,3}"));
        // 字符类里的量词字符是字面量
        assertEquals("正则配置成功", invokeDoRegex("[a+*.]{2,}\\w"));
    }

    /** 反射调私有 parseProperty，返回 [head, regex]；覆盖 k=v 解析与旧格式兼容 */
    private static String[] invokeParseProperty(String input) {
        try {
            Method method = ToolBash.class.getDeclaredMethod("parseProperty", String.class);
            method.setAccessible(true);
            Object property = method.invoke(null, input);
            Method head = property.getClass().getDeclaredMethod("head");
            Method regex = property.getClass().getDeclaredMethod("regex");
            head.setAccessible(true);
            regex.setAccessible(true);
            return new String[]{(String) head.invoke(property), (String) regex.invoke(property)};
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testParseProperty() {
        // k=v 双键；无 '=' 的杂散段忽略；未知键忽略
        String[] both = invokeParseProperty("bash=WSL;regex=(here-?doc|<<)");
        assertEquals("WSL", both[0]);
        assertEquals("(here-?doc|<<)", both[1]);
        assertNull(invokeParseProperty("randomtext")[0]);
        assertNull(invokeParseProperty("randomtext")[1]);
        assertNull(invokeParseProperty("foo=bar")[0]);
        assertNull(invokeParseProperty("foo=bar")[1]);
        // key/value 两侧空白容忍
        assertEquals("WSL", invokeParseProperty(" bash =WSL ;")[0]);
        // 空 value = 显式清除（head 重选 / regex 置 null）
        assertEquals("", invokeParseProperty("bash=")[0]);
        assertEquals("", invokeParseProperty("regex=")[1]);
        // 重复 key 以最后一个有效 value 为准
        assertEquals("GITBASH", invokeParseProperty("bash=WSL;bash=GITBASH")[0]);
        // 旧前缀格式（WSL/CUSTOM:/REGEX:）已随键前缀换 plugin-properties 一并放弃支持：
        // 无 '=' 的段一律忽略（老数据本就只在 dev 阶段存在）
        assertNull(invokeParseProperty("WSL")[0]);
        assertNull(invokeParseProperty("CUSTOM:C:\\x\\y\\bash.exe")[0]);
        assertNull(invokeParseProperty("REGEX:\\d+\\.sh")[1]);
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
        setPatternField(null);
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

    @Test
    void testUiTips() {
        String expected = """
                输入示例：k1=v1;k2=v2...
                bash=[WSL|GITBASH|{your_bash_path}];regex={your_to_file_regex}
                1. “” 空/空白 字符串零操作，返回当前bash和regex供查看。
                2. “bash=WSL” 指定"wsl bash"执行命令，若WSL有问题则工具拒绝执行之后的一切调用。建议开一个wsl bash窗口保持wsl在线，不然会有启动开销。
                3. “bash=GITBASH” 指定"C:\\Program Files\\Git\\bin\\bash.exe"执行命令，若内置git bash路径有问题，则工具拒绝执行之后的一切调用。
                4. “bash={your_bash_path}” 指定自定义bash可执行文件地址执行命令，若有问题，则仍使用内部定义的bash地址。为空则执行bash重选
                5. “regex={your_to_file_regex}” 指定命中正则的短命令转文件执行。为空则置null即短命令一律采用base64转码执行。
                重复key以最后一个有效value为准
                """;
        assertEquals(expected, ToolBash.getInstance().uiTips());
    }

    @Test
    void testUiInteractiveNoOpWhenInputBlank() {
        // 空白 = 零操作，只回显当前配置
        String result = ToolBash.getInstance().uiInteractive("  ");
        assertTrue(result.startsWith("当前bash："));
        assertTrue(result.contains("当前正则："));
    }

    @Test
    void testUiInteractiveWhenInputBashWsl() {
        String result = ToolBash.getInstance().uiInteractive("bash=WSL");
        assertTrue(result.startsWith("已重新指定为wsl bash，状态："));
        assertTrue(result.contains("当前正则："));
    }

    @Test
    void testUiInteractiveWhenInputBashGitBash() {
        String result = ToolBash.getInstance().uiInteractive("bash=GITBASH");
        assertTrue(result.startsWith("已重新指定为git bash，状态："));
    }

    @Test
    void testUiInteractiveWhenInputBashEmpty() {
        // bash= 值空 = 触发重选
        String result = ToolBash.getInstance().uiInteractive("bash=");
        assertTrue(result.startsWith("已执行bash重选，当前："));
    }

    @Test
    void testUiInteractiveWhenInputRegexEmpty() {
        // regex= 值空 = 置 null；bash 键缺省不动
        setPatternField(Pattern.compile("hello"));
        String result = ToolBash.getInstance().uiInteractive("regex=");
        assertTrue(result.contains("已重设转文件执行正则为空"));
        assertNull(getPatternField());
        assertTrue(result.startsWith("当前bash："));
    }
}
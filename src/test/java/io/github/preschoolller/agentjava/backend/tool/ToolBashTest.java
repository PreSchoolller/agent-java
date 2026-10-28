package io.github.preschoolller.agentjava.backend.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
                如果agent运行环境为Windows，则bash实现查找顺序(bash -> wsl bash -> git bash)。
                由于上述三种bash实现的路径表示不一样，可先pwd判断格式再执行绝对路径命令书写。
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
    void testExecute_Failed_WhenInvalid() {

    }
}
package io.github.preschoolller.agentjava.frontend.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 持久化往返单元测试（原 foragent/tools/Shot.java 的 PERSIST 段平移，逐条保留原断言文案）。
 * 纯模型操作不涉及 UI；临时目录整个类共享，各用例靠重新从磁盘读 store 承接上一步的落盘状态。
 */
@TestMethodOrder(OrderAnnotation.class)
class PersistenceTest {

    @TempDir
    static Path dir;

    /** 首个会话 id，后续用例按 id 找它（分叉后列表首位不一定是目标会话） */
    private static String convId;

    private static Path historyFile() {
        return dir.resolve("history.json");
    }

    @BeforeAll
    static void seed() {
        // 无需预置：各用例自己从磁盘承接
    }

    @Test
    @Order(1)
    void t01_roundTripTrimAndSessionFiles() throws Exception {
        // —— 持久化往返（含特殊字符）+ 删尾消息 ——
        ConversationStore first = new ConversationStore(historyFile());
        convId = first.newConversation().getId();
        first.appendMessage(convId, Conversation.ROLE_USER, "带\"引号\"、\n换行、\t制表和中文✓的消息");
        first.appendMessage(convId, Conversation.ROLE_AI, "回复");
        first.appendMessage(convId, Conversation.ROLE_AI, "将被删掉的旧回答");
        assertEquals("将被删掉的旧回答", first.removeLastMessage(convId).text(), "删尾返回值");
        assertEquals(2, first.getActive().getMessages().size(), "删尾后消息数");
        first.rename(convId, "往返测试标题");
        ConversationStore second = new ConversationStore(historyFile());
        assertEquals(1, second.getConversations().size(), "会话数");
        assertEquals(2, second.getActive().getMessages().size(), "消息数");
        assertTrue(second.getActive().getMessages().get(0).text().contains("\n"), "换行保留");
        assertEquals("回复", second.getActive().getMessages().get(1).text(), "删尾后落盘内容");
        assertEquals("往返测试标题", second.getActive().getTitle(), "标题");
        // 主文件只存元数据：messages 字段已移除，消息全部由 sessions/<会话id> 单文件承载
        Object mainRoot = JsonKit.parse(Files.readString(historyFile()));
        for (Object item : (List<?>) ((Map<?, ?>) mainRoot).get("conversations")) {
            assertFalse(((Map<?, ?>) item).containsKey("messages"), "主文件不含messages字段");
        }
        Object sessionRoot = JsonKit.parse(Files.readString(dir.resolve("sessions").resolve(convId)));
        assertTrue(sessionRoot instanceof List<?> sessionList && sessionList.size() == 2
                && "回复".equals(((Map<?, ?>) sessionList.get(1)).get("text")), "单会话文件承载消息");
    }

    @Test
    @Order(2)
    void t02_fork() {
        // 分叉：从 AI 回复分叉出新会话（含这条回复，从该状态继续），原会话不动，当前会话切到分叉
        ConversationStore second = new ConversationStore(historyFile());
        Conversation original = byId(second, assertConvId());
        Message forkPoint = original.getMessages().get(1);
        Conversation forked = second.fork(original.getId(), forkPoint);
        assertNotNull(forked, "分叉返回新会话");
        assertTrue(second.getActive().getId().equals(forked.getId()), "分叉后切到新会话");
        assertEquals(2, forked.getMessages().size(), "分叉会话消息数");
        assertEquals("带\"引号\"、\n换行、\t制表和中文✓的消息", forked.getMessages().get(0).text(), "分叉复制用户轮");
        assertEquals("回复", forked.getMessages().get(1).text(), "分叉含分叉点AI回复");
        assertEquals("往返测试标题（分叉）", forked.getTitle(), "分叉标题带来源");
        assertTrue(second.fork(forked.getId(), forked.getMessages().get(0)) == null, "用户消息不可分叉");
        assertEquals(2, second.getConversations().get(1).getMessages().size(), "原会话不动");
        ConversationStore third = new ConversationStore(historyFile());
        assertEquals(2, third.getConversations().size(), "分叉落盘会话数");
        assertTrue(third.getActive().getId().equals(forked.getId()), "分叉后活跃会话落盘");
    }

    @Test
    @Order(3)
    void t03_pinnedRoundTrip() {
        // 置顶落盘（v1 文件格式）：置顶→重读→取消置顶→重读（分叉后列表首位是新会话，按 id 找原会话）
        ConversationStore pinnedStore = new ConversationStore(historyFile());
        pinnedStore.setPinned(assertConvId(), true);
        assertTrue(pinnedOf(new ConversationStore(historyFile()), assertConvId()), "置顶落盘");
        new ConversationStore(historyFile()).setPinned(assertConvId(), false);
        assertFalse(pinnedOf(new ConversationStore(historyFile()), assertConvId()), "取消置顶落盘");
    }

    @Test
    @Order(4)
    void t04_emptyConversationCleanupAndCrashRecovery() throws Exception {
        // 删空活跃会话（重新生成走 removeLastMessage 删到最后一条）：主文件不再落盘，残留单文件清掉
        ConversationStore fifth = new ConversationStore(historyFile());
        String fifthId = fifth.getActive().getId();
        while (fifth.removeLastMessage(fifthId) != null) {
        }
        assertTrue(Files.notExists(dir.resolve("sessions").resolve(fifthId)), "删空会话清残留单文件");
        // 崩溃恢复（P0-5）：落盘的 running 工具消息（程序中途崩了/被杀的残留），重载后翻成"已中断"
        Files.createDirectories(dir.resolve("crash"));
        ConversationStore crashFirst = new ConversationStore(dir.resolve("crash/history.json"));
        String crashId = crashFirst.newConversation().getId();
        crashFirst.appendToolCall(crashId, "read_file", "{}", "call_crash");
        Message crashTool = new ConversationStore(dir.resolve("crash/history.json"))
                .getActive().getMessages().get(0);
        assertTrue(crashTool.isToolCall() && "interrupted".equals(crashTool.toolStatus()),
                "崩溃残留 running 翻成已中断");
    }

    @Test
    @Order(5)
    void t05_workspaceLifecycle() {
        // 工作区（计划外）：会话绑定目录落盘往返；分叉沿用；默认（null）不写键；空会话复用时改绑
        String wsPath = dir.resolve("ws-project").toString();
        ConversationStore wsStore = new ConversationStore(historyFile());
        wsStore.newConversation(wsPath);
        String activeId = wsStore.getActive().getId();
        wsStore.appendMessage(activeId, Conversation.ROLE_USER, "工作区测试");
        wsStore.appendMessage(activeId, Conversation.ROLE_AI, "好");
        Conversation wsForked = wsStore.fork(activeId, wsStore.getActive().getMessages().get(1));
        assertTrue(wsPath.equals(wsForked.getWorkspace()), "分叉沿用工作区");
        ConversationStore wsReread = new ConversationStore(historyFile());
        assertTrue(wsReread.getConversations().stream()
                .anyMatch(conversation -> wsPath.equals(conversation.getWorkspace())), "工作区落盘往返");
        ConversationStore wsDefaultStore = new ConversationStore(
                dir.resolve("ws-default").resolve("history.json"));
        Conversation defaultConv = wsDefaultStore.newConversation();
        assertNull(defaultConv.getWorkspace(), "默认工作区为 null");
        wsDefaultStore.appendMessage(defaultConv.getId(), Conversation.ROLE_USER, "x");
        assertNull(new ConversationStore(dir.resolve("ws-default/history.json"))
                .getActive().getWorkspace(), "默认工作区不落盘");
        // 空会话复用时改绑：当前是没说过话的空会话，再 new 一次直接改工作区而不是新建
        ConversationStore reuseStore = new ConversationStore(
                dir.resolve("reuse").resolve("history.json"));
        Conversation firstEmpty = reuseStore.newConversation();
        Conversation reusedConv = reuseStore.newConversation(wsPath);
        assertTrue(reusedConv.getId().equals(firstEmpty.getId()), "空会话复用");
        assertTrue(wsPath.equals(reusedConv.getWorkspace()), "复用空会话改绑工作区");
    }

    private static String assertConvId() {
        assertNotNull(convId, "首条会话未初始化（用例须按顺序执行）");
        return convId;
    }

    private static Conversation byId(ConversationStore store, String id) {
        for (Conversation conversation : store.getConversations()) {
            if (conversation.getId().equals(id)) {
                return conversation;
            }
        }
        throw new AssertionError("没找到会话: " + id);
    }

    /** 在仓库里按 id 找会话的置顶标记（分叉后列表首位不一定是目标会话） */
    private static boolean pinnedOf(ConversationStore store, String id) {
        for (Conversation conversation : store.getConversations()) {
            if (conversation.getId().equals(id)) {
                return conversation.isPinned();
            }
        }
        return false;
    }
}

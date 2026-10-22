package io.github.preschoolller.agentjava.frontend.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 按需加载单元测试（原 foragent/tools/Shot.java 的 LAZY 段平移）。 */
class LazyLoadTest {

    @TempDir
    Path dir;

    @Test
    void lazyLoading() throws Exception {
        // 按需加载：启动只读索引，会话文件延迟到首次触达消息才读；未加载的会话被改元数据时
        // 也不重写会话文件（mtime 不变），触碰消息后仍拿得到磁盘内容
        Path history = dir.resolve("history.json");
        ConversationStore lazyFirst = new ConversationStore(history);
        String lazyId = lazyFirst.newConversation().getId();
        lazyFirst.appendMessage(lazyId, Conversation.ROLE_USER, "按需加载的旧消息");
        Path lazySession = dir.resolve("sessions").resolve(lazyId);
        long lazyMtime = Files.getLastModifiedTime(lazySession).toMillis();
        Thread.sleep(30); // 会话文件先落盘，稍后靠 mtime 判断有没有被多余重写
        ConversationStore lazySecond = new ConversationStore(history);
        lazySecond.rename(lazySecond.getConversations().get(0).getId(), "改名但没读消息");
        assertEquals(lazyMtime, Files.getLastModifiedTime(lazySession).toMillis(), "元数据保存不重写会话文件");
        assertEquals(1, lazySecond.getActive().getMessages().size(), "首次触达时才读消息");
        assertEquals("按需加载的旧消息", lazySecond.getActive().getMessages().get(0).text(), "触碰消息拿得到磁盘内容");
    }
}

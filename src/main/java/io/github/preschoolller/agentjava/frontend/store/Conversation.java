package io.github.preschoolller.agentjava.frontend.store;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 一段对话：标题 + 消息列表，首条用户消息会自动截取为标题。 */
public class Conversation {

    public static final String ROLE_USER = "user";
    public static final String ROLE_AI = "ai";
    public static final String ROLE_TOOL = "tool";

    private final String id;
    private String title;
    private final long createdAt;
    private long updatedAt;
    private boolean pinned;
    private final List<Message> messages = new ArrayList<>();
    /** 消息是否已在内存。新建/分叉出来的会话天然为 true；从索引装载的为 false，首次触达时经 loader 补齐 */
    private boolean loaded;
    /** 单会话消息回读钩子（ConversationStore 装载索引时注入，消费一次即清空）；内存新建的会话为 null */
    private Supplier<List<Message>> loader;

    /** 装载索引用构造：只有元数据，消息等按需加载（attachLoader 注入回读钩子） */
    public Conversation(String id, String title, long createdAt, long updatedAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Conversation create() {
        long now = System.currentTimeMillis();
        Conversation conversation = new Conversation(UUID.randomUUID().toString(), "新对话", now, now);
        conversation.loaded = true;
        return conversation;
    }

    /** 注入消息回读钩子（包内：只有仓库需要）；必须在首次触达消息之前调用 */
    void attachLoader(Supplier<List<Message>> loader) {
        this.loader = loader;
    }

    /** 消息当前是否已在内存（仓库落盘判断用：未加载的会话磁盘上就是对的，不必重写） */
    boolean isLoaded() {
        return loaded;
    }

    /** 只有"确定加载过且为空"才算空；未加载的会话不触发读盘（落盘不变式：空会话从不进主文件） */
    boolean isKnownEmpty() {
        return loaded && messages.isEmpty();
    }

    /** 首次触达时把磁盘上的消息读进来；loader 读失败按无消息处理（仓库侧已打日志）。
     *  synchronized 只保证"读一次"：agent 后台线程和 EDT 可能先后触达同一会话 */
    private synchronized List<Message> ensureLoaded() {
        if (!loaded) {
            loaded = true;
            if (loader != null) {
                messages.addAll(loader.get());
                loader = null;
            }
        }
        return messages;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /** 置顶标记：侧栏排序时置顶优先（不 touch，updatedAt 保持"最后消息时间"语义） */
    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    /** 返回实时消息列表（未加载时先读盘补齐）；请勿在遍历时增删 */
    public List<Message> getMessages() {
        return ensureLoaded();
    }

    /** 消息是否已全在内存；未加载的会话会先触发读盘 */
    public boolean isEmpty() {
        return ensureLoaded().isEmpty();
    }

    public void setTitle(String title) {
        this.title = title;
        touch();
    }

    public Message addMessage(String role, String text) {
        List<Message> loadedMessages = ensureLoaded();
        Message message = new Message(role, text, System.currentTimeMillis());
        loadedMessages.add(message);
        touch();
        if (ROLE_USER.equals(role) && loadedMessages.size() == 1) {
            title = abbreviate(text, 20);
        }
        return message;
    }

    /** 追加一条工具调用消息（"运行中"占位，结果由 updateToolResult 回填），返回消息记录 */
    public Message addToolCall(String toolName, String toolArgs, String callId) {
        Message message = new Message(ROLE_TOOL, "", System.currentTimeMillis(), toolName, toolArgs, "running", callId);
        ensureLoaded().add(message);
        touch();
        return message;
    }

    /** 删除最后一条消息（重新生成流程：后端先删旧回答再重新请求），返回被删消息；空会话返回 null */
    public Message removeLast() {
        List<Message> loadedMessages = ensureLoaded();
        if (loadedMessages.isEmpty()) {
            return null;
        }
        Message removed = loadedMessages.remove(loadedMessages.size() - 1);
        touch();
        return removed;
    }

    /** 从某次对话状态分叉出新会话：prefix 为分叉点之前（含分叉点那条 AI 回复）的消息，
     *  标题沿用原会话标题加"（分叉）"后缀，侧栏里看得出来源 */
    public static Conversation fork(List<Message> prefix, String sourceTitle) {
        Conversation forked = create();
        for (Message message : prefix) {
            forked.messages.add(message.copy());
        }
        forked.title = abbreviate(sourceTitle, 20) + "（分叉）";
        return forked;
    }

    /** 流式输出结束后把已存在消息的文本替换为最终值（按消息对象同一性查找），返回替换后的新记录；没找到返回 null */
    public Message updateMessageText(Message target, String text) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == target) {
                Message updated = target.withText(text);
                messages.set(i, updated);
                touch();
                return updated;
            }
        }
        return null;
    }

    /** 工具调用完成：回填结果文本并写终态（按消息对象同一性查找），返回替换后的新记录；没找到返回 null */
    public Message updateToolResult(Message target, String result, boolean success, String arguments) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == target) {
                Message updated = new Message(target.role(), result, target.time(),
                        target.toolName(), arguments, success ? "ok" : "error", target.callId());
                messages.set(i, updated);
                touch();
                return updated;
            }
        }
        return null;
    }

    private void touch() {
        updatedAt = System.currentTimeMillis();
    }

    private static String abbreviate(String text, int max) {
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}

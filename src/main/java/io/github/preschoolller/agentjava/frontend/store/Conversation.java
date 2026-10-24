package io.github.preschoolller.agentjava.frontend.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 一段对话：标题 + 消息列表，首条用户消息会自动截取为标题。 */
public class Conversation {

    public static final String ROLE_USER = "user";
    public static final String ROLE_AI = "ai";
    public static final String ROLE_TOOL = "tool";

    /** 工具卡状态：命令已转入后台、当次只回了提交成功（终态等后台任务完成的回调回填） */
    public static final String STATUS_BG_RUNNING = "bg-running";
    /** 工具卡状态：后台任务已完成，输出已回填进结果区 */
    public static final String STATUS_BG_DONE = "bg-done";
    /** 工具卡状态：后台任务被用户手动终止（终态；与"已中断"区分——那是异常退出残留） */
    public static final String STATUS_BG_KILLED = "bg-killed";

    private final String id;
    private String title;
    private final long createdAt;
    private long updatedAt;
    private boolean pinned;
    /** 工作区（规范化的绝对路径）：本会话里 agent 工具的相对路径基准；null = 默认工作区
     *  （AppSettings.defaultWorkspace——默认位置以后可换，没绑过的会话跟着走） */
    private String workspace;
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
     *  synchronized 只保证"读一次"：agent 后台线程和 EDT 可能先后触达同一会话。
     *  loaded 先置位再读是刻意的：若读完再置位，loader 中途抛异常后二次触达会重读、
     *  已 addAll 的一半加上重读的全部 = 重复追加；一次性语义更稳——前提是 loader 实现
     *  自己吞掉所有异常（readSessionMessages 如此，换实现时别破坏这条契约）。 */
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

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
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
     *  标题沿用原会话标题加"（分叉）"后缀，工作区沿用源会话（分叉是同一工作区里继续对话），
     *  侧栏里看得出来源 */
    public static Conversation fork(List<Message> prefix, String sourceTitle, String workspace) {
        Conversation forked = create();
        for (Message message : prefix) {
            forked.messages.add(message.copy());
        }
        forked.title = abbreviate(sourceTitle, 20) + "（分叉）";
        forked.workspace = workspace;
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

    /** 工具调用完成：回填结果文本并写终态（按消息对象同一性查找），返回替换后的新记录；没找到返回 null。
     *  bash 后台提交是特例：成功翻 bg-running 等后台回调写终态；池满等提交失败（协议文案
     *  "background task submitted failed" 开头，ToolCall 仍为 COMPLETE）显式翻 error；
     *  前台任务被用户终止（结果带"(已手动终止)"标记）翻 bg-killed 复用"已终止"徽标 */
    public Message updateToolResult(Message target, String result, boolean success, String arguments) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == target) {
                String status = success ? "ok" : "error";
                if (success && isBackGroundSubmission(target.toolName(), arguments, result)) {
                    status = STATUS_BG_RUNNING;
                } else if (success && isFailedBackGroundSubmission(target.toolName(), result)) {
                    status = "error";
                } else if (success && isManuallyTerminated(result)) {
                    status = STATUS_BG_KILLED;
                }
                Message updated = new Message(target.role(), result, target.time(),
                        target.toolName(), arguments, status, target.callId(),
                        target.thinking());
                messages.set(i, updated);
                touch();
                return updated;
            }
        }
        return null;
    }

    /**
     * 后台提交识别：bash 参数声明了 background=true，且返回的是提交成功文案（成功/失败靠
     * 返回文案前缀区分——提交结果是给模型看的协议文案 ToolBash.executeBackGround，前端按
     * 同一前缀识别；参数不是合法 JSON 按前台处理）。
     */
    private static boolean isBackGroundSubmission(String toolName, String arguments, String result) {
        if (!"bash".equals(toolName) || arguments == null || result == null) {
            return false;
        }
        if (!result.startsWith("background task submitted successfully")) {
            return false;
        }
        try {
            Object raw = JsonKit.parse(arguments);
            return raw instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("background"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 后台提交失败识别：协议文案 "background task submitted failed" 前缀（池满等拒绝路径）。
     *  此时 ToolCall 状态仍为 COMPLETE，不显式翻 error 卡片会误显示"完成" */
    private static boolean isFailedBackGroundSubmission(String toolName, String result) {
        return "bash".equals(toolName) && result != null
                && result.startsWith("background task submitted failed");
    }

    /** 前台任务被手动终止识别：ToolBash.executeForeGround 在结果尾部写 "(已手动终止)"
     *  （exit code 是 kill 码没有参考价值），翻 bg-killed 复用"已终止"徽标。与后台提交
     *  文案同为前后端协议字符串，改动需两侧同步 */
    private static boolean isManuallyTerminated(String result) {
        return result != null && result.contains("(已手动终止)");
    }

    /**
     * 后台任务结束（markDone 回调，读线程 EOF 后触发，输出是完整缓冲）：按 callId 找
     * "后台运行中"→ 翻 bg-done 追加输出段；或找"已手动终止"（kill 先翻的卡）→ 把输出补进
     * 结果区、状态保持 bg-killed——终止快照拿不到管道排干的尾巴，完整输出以这里为准。
     * markDone 每任务恰好一次（bg 线程 finally 单次），两个分支都不会二次追加。
     * 其余情况返回 null（未知 callId）。返回非 null 时调用方负责注入通知与续轮。
     */
    public Message updateBackGroundResult(String callId, String output) {
        List<Message> loadedMessages = ensureLoaded();
        for (int i = loadedMessages.size() - 1; i >= 0; i--) {
            Message target = loadedMessages.get(i);
            if (callId == null || !callId.equals(target.callId())) {
                continue;
            }
            if (STATUS_BG_RUNNING.equals(target.toolStatus())) {
                String section = "—— 后台任务完成 ——\n"
                        + (output == null || output.isBlank() ? "(无输出)" : output.strip());
                Message updated = new Message(target.role(),
                        target.text() + "\n" + section, target.time(),
                        target.toolName(), target.toolArgs(), STATUS_BG_DONE, target.callId(),
                        target.thinking());
                loadedMessages.set(i, updated);
                touch();
                return updated;
            }
            if (STATUS_BG_KILLED.equals(target.toolStatus())) {
                Message updated = output == null || output.isBlank() ? target
                        : new Message(target.role(),
                                target.text() + "\n" + output.strip(), target.time(),
                                target.toolName(), target.toolArgs(), STATUS_BG_KILLED,
                                target.callId(), target.thinking());
                loadedMessages.set(i, updated);
                touch();
                return updated;
            }
        }
        return null;
    }

    /** 后台任务被手动终止：按 callId 找最后一条"后台运行中"的工具消息，追加终止标记并翻
     *  bg-killed 终态，返回替换后的新记录；没找到返回 null（重复终止/未知 callId）。
     *  输出不在这里带——终止时读线程还没排干管道，markDone 的完整输出随后经
     *  updateBackGroundResult 的 bg-killed 分支补进结果区 */
    public Message updateBackGroundKilled(String callId) {
        List<Message> loadedMessages = ensureLoaded();
        for (int i = loadedMessages.size() - 1; i >= 0; i--) {
            Message target = loadedMessages.get(i);
            if (callId != null && callId.equals(target.callId())
                    && STATUS_BG_RUNNING.equals(target.toolStatus())) {
                Message updated = new Message(target.role(),
                        target.text() + "\n—— 已手动终止 ——", target.time(),
                        target.toolName(), target.toolArgs(), STATUS_BG_KILLED, target.callId(),
                        target.thinking());
                loadedMessages.set(i, updated);
                touch();
                return updated;
            }
        }
        return null;
    }

    /** 流式结束：正文与独立思考一起替换（按消息对象同一性查找），返回替换后的新记录；没找到返回 null */
    public Message updateMessageContent(Message target, String text, String thinking) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == target) {
                Message updated = target.withContent(text, thinking);
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

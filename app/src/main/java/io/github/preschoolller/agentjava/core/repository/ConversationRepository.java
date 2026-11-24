package io.github.preschoolller.agentjava.core.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.preschoolller.agentjava.spi.common.Constants;
import io.github.preschoolller.agentjava.spi.enums.AgentMode;
import io.github.preschoolller.agentjava.spi.enums.ReasoningEffortEnum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;

public class ConversationRepository {

    /**
     * 历史文件格式版本：v1 = {"version":1,"conversations":[...]}。以后加字段先升它再改 load
     */
    private static final int HISTORY_VERSION = 1;

    protected final Path file;
    // 单会话文件目录（文件名即会话 id）
    protected final Path sessionsDir;

    protected final List<Conversation> conversations = new ArrayList<>();
    protected final List<Runnable> changeListeners = new ArrayList<>();

    public ConversationRepository() {
        this(Constants.APP_USER_DIR.resolve("history.json"));
    }

    public ConversationRepository(Path file) {
        this.file = file;
        this.sessionsDir = file.getParent().toAbsolutePath().resolve("sessions");
        load();
    }

    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /**
     * 注销监听（侧栏随主窗口重建换新时，旧实例用它解除订阅）
     */
    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    protected void fireChanged() {
        changeListeners.forEach(Runnable::run);
    }

    public List<Conversation> getConversations() {
        return List.copyOf(conversations);
    }

    /**
     * 同 newConversation(workspace)，用默认工作区
     */
    public Conversation newConversation() {
        return newConversation(null);
    }

    /**
     * 新建一段对话；
     * workspace 为会话绑定的工作区目录（规范化绝对路径），null = 默认工作区
     */
    public Conversation newConversation(String workspace) {
        Conversation created = Conversation.create();
        created.attachSaver(() -> saveAndSyncSession(created));
        created.setWorkspace(workspace);
        conversations.add(0, created);
        save();
        fireChanged();
        return created;
    }

    /**
     * 按 id 查找会话；不存在就用该 id 新建（外部注入用：外部会话 id 必须稳定映射到同一会话，
     * 如 dsh-shim 的 "bili-whale"）。与 newConversation 不同：不复用现有空会话。
     * 须在 EDT 上调用（save/fireChanged 与既有落库路径同约束）。
     */
    public synchronized Conversation getOrCreate(String id, String workspace) {
        Conversation existing = findById(id);
        if (existing != null) {
            return existing;
        }
        Conversation created = new Conversation(id, "", System.currentTimeMillis(), System.currentTimeMillis());
        created.attachSaver(() -> saveAndSyncSession(created));
        created.setWorkspace(workspace);
        conversations.add(0, created);
        save();
        fireChanged();
        return created;
    }

    public void rename(String id, String title) {
        Conversation conversation = findById(id);
        if (conversation == null || title.isBlank()) {
            return;
        }
        conversation.setTitle(title.strip());
        saveAndSyncSession(conversation);
        fireChanged();
    }

    /**
     * 置顶/取消置顶（侧栏排序时置顶优先），变更落盘并通知
     */
    public void setPinned(String id, boolean pinned) {
        Conversation conversation = findById(id);
        if (conversation == null || conversation.isPinned() == pinned) {
            return;
        }
        conversation.setPinned(pinned);
        saveAndSyncSession(conversation);
        fireChanged();
    }

    public void remove(String id) {
        conversations.removeIf(conversation -> conversation.getId().equals(id));
        deleteSessionFile(id);
        save();
        fireChanged();
    }

    /**
     * 向指定会话追加一条消息，会话同时挪到"最近使用"首位
     */
    public Message appendMessage(String id, Message.Role role, String text) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        Message message = conversation.addMessage(role, text);
        conversations.remove(conversation);
        conversations.add(0, conversation);
        saveAndSyncSession(conversation);
        fireChanged();
        return message;
    }

    /**
     * 追加上下文压缩水位线标记（Main 的压缩轮在 handle 成功后于 EDT 调用）：
     * 落盘走 saveAndSyncSession 与消息同机制（后台会话的标记也落自己的库），
     * 不挪"最近使用"位、fireChanged 对显示中的会话是无操作（onStoreChanged 只在
     * 活动会话变化时重建瀑布流），所以标记不会出卡——它本来就不渲染。
     * 返回标记消息记录；会话不存在返回 null。
     */
    public Message appendContextMarker(String id) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            return null;
        }
        Message message = conversation.addContextMarker();
        saveAndSyncSession(conversation);
        fireChanged();
        return message;
    }

    /**
     * 从指定会话的一条 AI 回复处分叉出新会话并设为当前：分叉点之前（含这条回复）的消息
     * 原样复制进新会话，从该对话状态继续，原会话不动
     */
    public Conversation fork(String id, String targetId) {
        Conversation source = findById(id);
        if (source == null) {
            return null;
        }
        List<Message> messages = source.getMessages();
        int cut = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (Objects.equals(messages.get(i).messageId(), targetId)) {
                if (!Message.Role.ROLE_AI.equals(messages.get(i).role())) {
                    return null;
                }
                cut = i;
                break;
            }
        }
        if (cut < 0) {
            return null;
        }
        Conversation forked = Conversation.fork(messages.subList(0, cut + 1), source.getTitle(),
                source.getWorkspace());
        forked.attachSaver(() -> saveAndSyncSession(forked));
        conversations.add(0, forked);
        saveAndSyncSession(forked);
        fireChanged();
        return forked;
    }

    /**
     * 删除指定会话的最后一条消息（重新生成时由后端先删旧回答），返回被删消息；没有则返回 null
     */
    public Message removeLastMessage(String id) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            return null;
        }
        Message removed = conversation.removeLast();
        if (removed != null) {
            saveAndSyncSession(conversation);
            fireChanged();
        }
        return removed;
    }

    /**
     * 流式输出结束的落库：正文与独立思考一起写回，返回替换后的新记录
     */
    public Message updateMessageContent(String id, String target, String text, String thinking) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            return null;
        }
        Message updated = conversation.updateMessageContent(target, text, thinking);
        if (updated != null) {
            saveAndSyncSession(conversation);
            fireChanged();
        }
        return updated;
    }

    /**
     * 追加一条工具调用消息（"运行中"占位，agent 循环每次调工具时先调这个），返回消息记录
     */
    public Message appendToolCall(String id, String toolName, String argsSummary, String callId) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在: " + id);
        }
        Message message = conversation.addToolCall(toolName, argsSummary, callId);
        conversations.remove(conversation);
        conversations.add(0, conversation);
        saveAndSyncSession(conversation);
        fireChanged();
        return message;
    }

    /**
     * 工具执行完成后的落库：回填结果并写终态，返回替换后的新记录
     */
    public Message completeToolCall(String id, String target, String result, boolean success, String arguments) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            return null;
        }
        Message updated = conversation.updateToolResult(target, result, success, arguments);
        if (updated != null) {
            saveAndSyncSession(conversation);
            fireChanged();
        }
        return updated;
    }

    /**
     * 后台任务完成的落库（agent 循环 markDone 回调）：按 callId 找到"后台运行中"的工具消息，
     * 追加输出并翻"后台完成"终态；返回替换后的新记录，会话不存在/没找到返回 null
     */
    public Message completeBackGroundTask(String id, String callId, String output) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            return null;
        }
        Message updated = conversation.updateBackGroundResult(callId, output);
        if (updated != null) {
            saveAndSyncSession(conversation);
            fireChanged();
        }
        return updated;
    }

    /**
     * 后台任务被手动终止的落库（BackGroundTaskControl.kill 实现方回调）：按 callId 找到
     * "后台运行中"的工具消息翻 bg-killed 终态；返回替换后的新记录，会话不存在/没找到返回 null。
     * 输出不在这里带：markDone（EOF 后的完整缓冲）随后经 completeBackGroundTask 的
     * bg-killed 分支补进结果区并触发注入/续轮
     */
    public Message terminateBackGroundTask(String id, String callId) {
        Conversation conversation = findById(id);
        if (conversation == null) {
            return null;
        }
        Message updated = conversation.updateBackGroundKilled(callId);
        if (updated != null) {
            saveAndSyncSession(conversation);
            fireChanged();
        }
        return updated;
    }

    /**
     * 按会话 id 取会话（后台任务终止等按 id 定向的场景用，Main 不持有 Conversation 对象）；不存在返回 null
     */
    public Conversation getConversation(String id) {
        return id == null ? null : findById(id);
    }

    protected Conversation findById(String id) {
        if (id == null) {
            return null;
        }
        for (Conversation conversation : conversations) {
            if (conversation.getId().equals(id)) {
                return conversation;
            }
        }
        return null;
    }

    /**
     * 仅保存元数据，会话由钩子保存
     */
    public synchronized void save() {
        List<Object> array = new ArrayList<>();
        for (Conversation conversation : conversations) {
            if (conversation.isKnownEmpty()) {
                deleteSessionFile(conversation.getId());
                continue;
            }
            Map<String, Object> map = serializeConversation(conversation);
            array.add(map);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", HISTORY_VERSION);
        root.put("conversations", array);
        try {
            // 临时文件写完再原子改名：写一半崩溃时旧文件完好无损
            Path tempPath = file.resolveSibling("temp-" + file.getFileName());
            Files.createDirectories(file.getParent());
            Files.writeString(tempPath, JsonKit.write(root));
            Files.move(tempPath, file, ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("保存会话历史失败: " + e.getMessage());
        }
    }

    public void saveAndSyncSession(Conversation changed) {
        save();
        if (!changed.isLoaded() || changed.isKnownEmpty()) {
            return;
        }
        saveSession(changed);
    }

    public void saveSession(String conversationId) {
        Conversation conversation = findById(conversationId);
        if (conversation == null) {
            return;
        }
        saveSession(conversation);
    }

    public void saveSession(Conversation conversation) {
        syncNewHistory(serializeMessages(conversation), conversation.getId());
    }

    private Map<String, Object> serializeConversation(Conversation conversation) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", conversation.getId());
        map.put("title", conversation.getTitle());
        map.put("createdAt", conversation.getCreatedAt());
        map.put("updatedAt", conversation.getUpdatedAt());
        map.put("pinned", conversation.isPinned());
        if (conversation.getWorkspace() != null) {
            map.put("workspace", conversation.getWorkspace()); // null（默认工作区）不写键
        }
        if (conversation.getModelName() != null) {
            // 会话级模型记忆（输入区下拉恢复用）；缺键（旧文件/没发过消息）= 未记录
            map.put("model", conversation.getModelName());
            map.put("modelProfile", conversation.getModelProfile());
        }
        if (conversation.getReasoningEffort() != null || conversation.getAgentMode() != null) {
            // 会话级偏好记忆（思考强度/权限模式，发送时盖戳）；缺键（旧文件）= 未记录
            if (conversation.getReasoningEffort() != null) {
                map.put("reasoningEffort", conversation.getReasoningEffort().name());
            }
            if (conversation.getAgentMode() != null) {
                map.put("agentMode", conversation.getAgentMode().name());
            }
        }
        if (conversation.getUsage() != null) {
            // 最近一轮用量（胶囊恢复 + 以后上下文管理用）；缺键（旧文件/没跑过轮）= 未记录
            Conversation.Usage usage = conversation.getUsage();
            map.put("usageInput", usage.inputTokens());
            map.put("usageOutput", usage.outputTokens());
            map.put("usageElapsed", usage.elapsedSeconds());
        }
        return map;
    }

    protected static String serializeMessages(Conversation conversation) {
        List<Object> messages = new ArrayList<>(conversation.getMessages());
        return JsonKit.toJsonString(messages);
    }

    protected void syncNewHistory(String messages, String conversationId) {
        // 同步保存单个会话到 sessions/<会话id>，同样走临时文件 + 原子改名
        try {
            Path tempPath = sessionsDir.resolve("temp-" + conversationId);
            Path realPath = sessionsDir.resolve(conversationId);
            Files.createDirectories(sessionsDir);
            Files.writeString(tempPath, messages);
            Files.move(tempPath, realPath, ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("保存单会话历史失败: " + conversationId + " " + e.getMessage());
        }
    }

    protected void deleteSessionFile(String id) {
        try {
            Files.deleteIfExists(sessionsDir.resolve(id));
        } catch (IOException e) {
            System.err.println("删除单会话历史失败，id：" + id + " " + e.getMessage());
        }
    }

    protected void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            Object root = JsonKit.parse(Files.readString(file));
            // v1 起从 conversations 字段取。
            // 以后升版本时在 version 分支里做字段迁移，别把用户旧历史读丢。
            List<?> items = null;
            if (root instanceof Map<?, ?> map) {
                items = (List<?>) map.get("conversations");
            }
            if (items == null) {
                return;
            }
            for (Object item : items) {
                Map<?, ?> map = (Map<?, ?>) item;
                Conversation conversation = new Conversation(
                        str(map.get("id")), str(map.get("title")),
                        num(map.get("createdAt")), num(map.get("updatedAt")));
                conversation.setPinned(Boolean.TRUE.equals(map.get("pinned")));
                Object workspace = map.get("workspace"); // 缺键（旧文件/默认工作区）= null
                if (workspace != null && !String.valueOf(workspace).isBlank()) {
                    conversation.setWorkspace(String.valueOf(workspace));
                }
                Object modelName = map.get("model"); // 会话级模型记忆：缺键（旧文件/没发过消息）= 未记录
                if (modelName != null && !String.valueOf(modelName).isBlank()) {
                    Object modelProfile = map.get("modelProfile");
                    conversation.setModelOption(
                            modelProfile == null ? null : String.valueOf(modelProfile),
                            String.valueOf(modelName));
                }
                Object effortName = map.get("reasoningEffort"); // 会话级偏好记忆：缺键（旧文件）= 未记录
                Object modeName = map.get("agentMode");
                if (effortName != null || modeName != null) {
                    conversation.setDialogPreferences(
                            parseEnum(ReasoningEffortEnum.class, effortName),
                            parseEnum(AgentMode.class, modeName));
                }
                Object usageInput = map.get("usageInput"); // 最近一轮用量：三键齐才算有（防半截数据）
                if (usageInput != null && map.get("usageOutput") != null && map.get("usageElapsed") != null) {
                    conversation.setUsage(new Conversation.Usage(
                            num(usageInput), num(map.get("usageOutput")), dbl(map.get("usageElapsed"))));
                }
                // 按需加载：启动只读元数据，消息挂回读钩子，首次触达该会话消息时才读单会话文件。
                // 消息只认单会话文件（文件名=会话 id）——旧格式内嵌消息不迁移，读到坏文件按无消息处理
                String id = conversation.getId();
                conversation.attachLoader(() -> readSessionMessages(id));
                conversation.attachSaver(() -> saveAndSyncSession(conversation));
                conversations.add(conversation);
            }
        } catch (Exception e) {
            System.err.println("读取会话历史失败（忽略旧文件）: " + e.getMessage());
        }
    }

    /**
     * 读单个会话文件并转成消息列表；文件缺失或损坏按无消息处理（只在该会话消息首次被触达时调用一次）
     */
    private List<Message> readSessionMessages(String id) {
        List<Message> messages = new ArrayList<>();
        Path sessionFile = sessionsDir.resolve(id);
        if (!Files.exists(sessionFile)) {
            return messages; // 从没说过话的会话没有单文件，不是错误
        }
        try {
            List<Message> raw = JsonKit.parse(Files.readString(sessionFile), new TypeReference<>() {});
            for (Message item : raw) {
                // 崩溃恢复（P0-5）：running 只该存在于活着的进程内存里，读盘遇到就是上次崩了/被杀的
                // 残留，翻成 interrupted（前端显示"已中断"灰态）；其空结果回传时本就被
                // buildRoleMessages 的空文本跳过，不会发给后端。
                // bg-running 同理：后台进程已随 ShutDownJob 的退出清理被杀，回不到"后台完成"了
                Message.ToolStatus status = item.toolStatus();
                if (Message.ToolStatus.RUNNING.equals(status) || Message.ToolStatus.STATUS_BG_RUNNING.equals(status)) {
                    status = Message.ToolStatus.INTERRUPTED;
                }
                item = item.withStatus(item.toolName() == null ? null : status);
                if (item.messageId() == null) {
                    item = item.withMessageId(UUID.randomUUID().toString());
                }
                messages.add(item);
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("读取单会话历史出错，该会话按无消息加载: " + id + " " + exception.getMessage());
            return new ArrayList<>(); // 半截文件：已解析出来的也不要用，展示空会话比展示半截历史好
        }
        return messages;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double dbl(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * 枚举名 → 枚举值；null/未知名（旧文件遇到被删改的枚举值）回落 null = 未记录
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, Object name) {
        if (name == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, String.valueOf(name));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

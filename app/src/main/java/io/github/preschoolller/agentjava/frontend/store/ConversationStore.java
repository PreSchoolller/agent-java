package io.github.preschoolller.agentjava.frontend.store;

import io.github.preschoolller.agentjava.core.repository.ConversationRepository;

import java.nio.file.Path;

/**
 * 会话仓库：内存里只常驻会话索引（元数据）+ 消息按需加载，本地 JSON 持久化
 * （默认 ~/.agentjava/history.json，构造时可换文件）。启动只读主文件，
 * 单会话消息文件在首次触达该会话消息时才读（Conversation.getMessages 触发 loader 读一次）。
 * 列表保持"最近使用在前"，侧栏渲染时置顶会话优先（置顶只改标记不动仓库顺序）。
 * 任何变更都会通知 changeListeners（侧栏刷新、主区切换会话都靠它）。
 */
public class ConversationStore extends ConversationRepository {

    private String activeId;

    public ConversationStore() {
        super();
    }

    public ConversationStore(Path file) {
        super(file);
    }

    /** 当前会话；仓库为空时自动建一段空对话（不触发变更事件） */
    public Conversation getActive() {
        Conversation active = findById(activeId);
        if (active != null) {
            return active;
        }
        if (conversations.isEmpty()) {
            Conversation created = Conversation.create();
            created.attachSaver(() -> saveAndSyncSession(created));
            conversations.add(created);
            activeId = created.getId();
            return created;
        }
        active = conversations.get(0);
        activeId = active.getId();
        return active;
    }

    /** 新建一段对话并设为当前；当前已是空对话时直接复用（并把工作区改成所选的），避免堆出一串空会话。
     *  workspace 为会话绑定的工作区目录（规范化绝对路径），null = 默认工作区 */
    public Conversation newConversation(String workspace) {
        Conversation current = findById(activeId);
        if (current != null && current.isKnownEmpty()) {
            if (!java.util.Objects.equals(current.getWorkspace(), workspace)) {
                current.setWorkspace(workspace);
                save();
                fireChanged();
            }
            return current;
        }
        Conversation created = super.newConversation(workspace);
        created.attachSaver(() -> saveAndSyncSession(created));
        activeId = created.getId();
        return created;
    }

    public void switchTo(String id) {
        if (id.equals(activeId) || findById(id) == null) {
            return;
        }
        Conversation current = findById(activeId);
        if (current != null && current.isKnownEmpty()) {
            conversations.remove(current); // 离开没说过话的空会话时顺手清掉（未加载的会话不读盘，也绝不删）
            deleteSessionFile(current.getId());
        }
        activeId = id;
        save();
        fireChanged();
    }

    public void remove(String id) {
        if (id.equals(activeId)) {
            activeId = conversations.isEmpty() ? null : conversations.get(0).getId();
        }
        super.remove(id);
    }

    /** 从指定会话的一条 AI 回复处分叉出新会话并设为当前：分叉点之前（含这条回复）的消息
     *  原样复制进新会话，从该对话状态继续，原会话不动 */
    public Conversation fork(String id, Message target) {
        Conversation forked = super.fork(id, target);
        if (forked == null) {
            return null;
        }
        activeId = forked.getId();
        return forked;
    }

    /** 空会话不落盘（残留单文件一并清理），避免历史里堆满"新对话"；活跃会话顺带同步到单会话文件。
     *  按需加载下这里只碰元数据：未加载的会话磁盘上就是对的，既不读也不写，绝不被空列表覆盖 */
    public synchronized void save() {
        super.save();
        saveSession(activeId);
    }

    /** 全量落盘 + 单会话文件同步：活跃会话的副本 save() 内已写，这里补刷非活跃会话的变更 */
    public void saveAndSyncSession(Conversation changed) {
        save();
        // 活跃会话 save() 内已同步；空会话的单文件由 save() 统一清理，这里再补刷只会写回空数组。
        // 未加载的会话磁盘上就是对的（rename/setPinned 只改元数据，不碰消息），补刷反而会整文件读进来重写
        if (!changed.isLoaded() || changed.getId().equals(activeId) || changed.isKnownEmpty()) {
            return;
        }
        syncNewHistory(serializeMessages(changed), changed.getId());
    }

    protected void load() {
        super.load();
        activeId = conversations.isEmpty() ? null : conversations.get(0).getId();
    }
}

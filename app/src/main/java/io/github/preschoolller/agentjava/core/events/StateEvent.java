package io.github.preschoolller.agentjava.core.events;

/**
 * 应用状态事件流。sealed 收口：前端只能穷举 permits 列出的这些事件，
 * 新增事件必须同时改这里和各前端的 switch（编译器强制）。
 *
 * <p>事件类型与父接口同包散放：未命名模块下 sealed 的许可子类必须与父类型同包，
 * 所以 StateEvent 跟着事件一起落在 core.events，而不是 core.interfaces。
 */
public sealed interface StateEvent
        permits SessionCreated, SessionRemoved, SessionRenamed, SessionPinned,
        MessageAppended, MessageDelta, MessageCompleted, ToolStateChanged,
        UserMessageConfirmed, UserMessageRejected,
        RoundStarted, RoundFinished, RoundFailed, RoundCancelled,
        HostRequestOpened, HostRequestClosed, ConfigChanged, Notice, GapDetected {

    long seq();

    /**
     * 不绑定会话的事件（全局配置变更、流级控制信号）返回 null
     */
    default String sessionId() {
        return null;
    }
}
package io.github.preschoolller.agentjava.core.events;

/**
 * 不带新值，只是「这项变了」的信号：前端收到后重拉对应查询
 * （会话级 → session(id)，全局 → profiles() / enabledTools() 等）。
 */
public record ConfigChanged(String key, String sessionId, long seq) implements StateEvent {
}
package io.github.preschoolller.agentjava.core.events;

/**
 * 流级控制信号：sessionId() 恒为 null（由 StateEvent 的默认实现提供）。
 * 收到后重拉快照并丢弃本地增量，见设计文档 8.2。
 */
public record GapDetected(long sinceSeq, long earliestSeq, long seq) implements StateEvent {
}
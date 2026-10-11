package io.github.preschoolller.agentjava.backend.provider.common.event;

/** 响应完结事件：带上本轮的 token 用量（completed 事件的 response.usage，缺省为 0） */
public record ResponseCompleteEvent(long inputTokens, long outputTokens) implements BaseEvent {
}

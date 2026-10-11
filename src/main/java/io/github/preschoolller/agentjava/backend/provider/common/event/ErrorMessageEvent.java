package io.github.preschoolller.agentjava.backend.provider.common.event;

/** 流式错误事件：provider 把错误文本包进这个事件扔进队列，Main 收到走通知气泡展示。
 *  前端能力就绪；后端在 visitError/visitFailed/visitIncomplete/SSE 异常处发射（待接线），
 *  通常与 PoisonEvent 一起发：先错误、后毒丸收束 */
public record ErrorMessageEvent(String message) implements BaseEvent {
}

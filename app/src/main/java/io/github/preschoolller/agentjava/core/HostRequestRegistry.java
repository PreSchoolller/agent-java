package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.core.interfaces.HostRequests;

import java.util.function.Consumer;
import java.util.function.Function;

public interface HostRequestRegistry {

    /**
     * 创建一个 HostRequests.Request 并发送 HostRequestOpened 事件
     * @param answerConsumer 消费前端 Answer 结果
     * @param requestIdForRequest 给出 requestId 并返回对应 HostRequests.Request 对象，会校验该对象 requestId，与提供的不一致会导致 IllegalArgumentsException
     * @return requestId
     */
    String makeRequest(Consumer<HostRequests.Answer> answerConsumer, Function<String, HostRequests.Request> requestIdForRequest);

    /**
     * 撤销 HostRequests.Request，若不存在 no-op；若存在则 answer cancel；
     *
     * @param requestId 要撤销的 HostRequests.Request id
     * @param reason 撤销原因，非 null 则代发 HostRequestClosed 事件
     */
    void drawbackRequest(String requestId, String reason);
}

package io.github.preschoolller.agentjava.backend.provider.common;

import io.github.preschoolller.agentjava.backend.provider.common.event.BaseEvent;

import java.util.concurrent.BlockingQueue;

public record ProviderStreamingResponse(BlockingQueue<BaseEvent> blockingQueue) {
}

package io.github.preschoolller.agentjava.spi.provider.records;

import io.github.preschoolller.agentjava.spi.provider.event.BaseEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public record ProviderStreamingResponse(BlockingQueue<BaseEvent> blockingQueue,
                                        AtomicBoolean shouldStop) {
    public ProviderStreamingResponse(BlockingQueue<BaseEvent> blockingQueue) {
        this(blockingQueue, null);
    }
}

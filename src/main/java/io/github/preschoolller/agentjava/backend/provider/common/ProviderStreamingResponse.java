package io.github.preschoolller.agentjava.backend.provider.common;

import java.util.concurrent.BlockingQueue;

public record ProviderStreamingResponse(BlockingQueue<String> blockingQueue, BlockingQueue<ToolCall> toolCalls) {
}

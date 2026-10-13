package io.github.preschoolller.agentjava.backend.provider.common;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolFactory {

    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    private static final ThreadFactory toolCallThreadFactory = new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(0);

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(false);
            thread.setName("toolcall-pool-1-thread-" + count.incrementAndGet());
            return thread;
        }
    };


    private static final ThreadPoolExecutor toolCallExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE, 100, 1000, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100, true), toolCallThreadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy());

    private static final ThreadFactory responseThreadFactory = new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(0);

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("response-pool-1-thread-" + count.incrementAndGet());
            return thread;
        }
    };

    private static final ThreadPoolExecutor responseExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE, 100, 1000, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100, true), responseThreadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy());


    public static ThreadPoolExecutor getToolCallExecutor() {
        return toolCallExecutor;
    }

    public static ThreadPoolExecutor getResponseExecutor() {
        return responseExecutor;
    }
}

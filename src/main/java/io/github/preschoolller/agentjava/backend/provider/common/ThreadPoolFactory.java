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
            thread.setDaemon(true);
            thread.setName("toolcall-pool-1-thread-" + count.incrementAndGet());
            return thread;
        }
    };


    private static final ThreadPoolExecutor toolCallExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE, 100, 1000, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100, true), toolCallThreadFactory,
            new ThreadPoolExecutor.CallerRunsPolicy());

    public static ThreadPoolExecutor getToolCallExecutor() {
        return toolCallExecutor;
    }


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

    public static ThreadPoolExecutor getResponseExecutor() {
        return responseExecutor;
    }



    private static final ThreadFactory bgTaskThreadFactory = new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(0);

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("bgTask-pool-1-thread-" + count.incrementAndGet());
            return thread;
        }
    };

    /**
     * 固定大小 CORE_POOL_SIZE ，限制后台进程数量，进不去明确告知后台任务创建失败
     */
    private static final ThreadPoolExecutor bgTaskExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE, CORE_POOL_SIZE + 1, 1000, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(CORE_POOL_SIZE + 1, true), bgTaskThreadFactory,
            new ThreadPoolExecutor.AbortPolicy());


    public static ThreadPoolExecutor getBgTaskExecutor() {
        return bgTaskExecutor;
    }



    private static final ThreadFactory bgTaskReadOutThreadFactory = new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(0);

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("bgTaskReadOut-pool-1-thread-" + count.incrementAndGet());
            return thread;
        }
    };

    /**
     * 参数同 bgTaskExecutor
     */
    private static final ThreadPoolExecutor bgTaskReadOutExecutor = new ThreadPoolExecutor(
            bgTaskExecutor.getCorePoolSize(), bgTaskExecutor.getMaximumPoolSize(), 1000, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(CORE_POOL_SIZE + 1, true), bgTaskReadOutThreadFactory,
            new ThreadPoolExecutor.AbortPolicy());


    public static ThreadPoolExecutor getBgTaskReadOutExecutor() {
        return bgTaskReadOutExecutor;
    }



    private static final ThreadFactory readBashOutFactory = new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(0);

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("readBashOut-pool-1-thread-" + count.incrementAndGet());
            return thread;
        }
    };

    /**
     * 参数同 toolCallExecutor
     */
    private static final ThreadPoolExecutor readBashOutExecutor = new ThreadPoolExecutor(
            toolCallExecutor.getCorePoolSize(), toolCallExecutor.getMaximumPoolSize(),
            toolCallExecutor.getKeepAliveTime(TimeUnit.SECONDS), TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100, true), readBashOutFactory,
            new ThreadPoolExecutor.AbortPolicy());


    public static ThreadPoolExecutor getReadBashOutExecutor() {
        return readBashOutExecutor;
    }
}

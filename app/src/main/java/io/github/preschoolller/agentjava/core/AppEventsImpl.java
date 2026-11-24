package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.backend.maintenance.ShutDownJob;
import io.github.preschoolller.agentjava.core.events.GapDetected;
import io.github.preschoolller.agentjava.core.events.StateEvent;
import io.github.preschoolller.agentjava.core.interfaces.AppEvents;
import io.github.preschoolller.agentjava.core.interfaces.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class AppEventsImpl implements AppEvents {

    private static final List<EventListener> listeners = new CopyOnWriteArrayList<>();
    private static final int STORAGE_CAPACITY = 4096;
    private static final int PUBLISH_CAPACITY = 1000;
    private static final int EMPTY_SPACE = 1000;
    private static final BlockingQueue<StateEvent> storage = new ArrayBlockingQueue<>(STORAGE_CAPACITY + EMPTY_SPACE);
    private static final BlockingQueue<StateEvent> buffer = new ArrayBlockingQueue<>(PUBLISH_CAPACITY);
    private static long seq = 0L;
    private static final Logger LOG = LoggerFactory.getLogger(AppEventsImpl.class);

    private AppEventsImpl() {
        Thread broadcast = new Thread(AppEventsImpl::broadcast);
        broadcast.setDaemon(true);
        broadcast.setName("app-events-broadcast-thread");
        broadcast.start();

        ShutDownJob.addShutDownHook(broadcast::interrupt);
    }

    public static AppEventsImpl getInstance() {
        return InstanceHolder.INSTANCE;
    }

    private static class InstanceHolder {
        private static final AppEventsImpl INSTANCE = new AppEventsImpl();
    }

    @Override
    public AutoCloseable subscribe(long sinceSeq, EventListener listener) {
        StateEvent peek = storage.peek();
        long earliestSeq = peek == null ? 0 : peek.seq();
        if (sinceSeq + 1 < earliestSeq) {
            // GapDetected 不走统一广播 seq 没意义，传 0
            listener.onEvent(new GapDetected(sinceSeq, earliestSeq, -1));
        } else {
            for (StateEvent event : storage) {
                if (event.seq() > sinceSeq) {
                    listener.onEvent(event);
                }
            }
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public static synchronized void publish(Function<Long, StateEvent> function) {
        StateEvent event = function.apply(seq);
        if (event.seq() != seq) {
            throw new IllegalArgumentException("sequence mismatch");
        }
        seq++;
        try {
            buffer.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while waiting for event enqueue buffer");
        }
    }

    private static void broadcast() {
        while (true) {
            try {
                StateEvent event = buffer.take();
                if (storage.size() >= STORAGE_CAPACITY) {
                    makeSpace();
                }
                storage.put(event);
                for (EventListener listener : listeners) {
                    try {
                        listener.onEvent(event);
                    } catch (Exception ignore) {
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting for event enqueue storage");
            }
        }
    }

    private static void makeSpace() {
        for (int i = 0; i < EMPTY_SPACE; i++) {
            storage.poll();
        }
    }
}

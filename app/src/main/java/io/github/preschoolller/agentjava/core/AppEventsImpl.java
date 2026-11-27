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

public class AppEventsImpl implements AppEvents, EventPublisher, Runnable {

    private static final int STORAGE_CAPACITY = 4096;
    private static final int PUBLISH_CAPACITY = 1000;
    private static final int EMPTY_SPACE = 1000;
    private static final Logger LOG = LoggerFactory.getLogger(AppEventsImpl.class);
    private static final long POLL_MS = 5;

    private long seq = 0L;

    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();
    private final BlockingQueue<StateEvent> buffer = new ArrayBlockingQueue<>(PUBLISH_CAPACITY);
    private final BlockingQueue<StateEvent> storage = new ArrayBlockingQueue<>(STORAGE_CAPACITY + EMPTY_SPACE);

    private int publishDepth = 0;

    AppEventsImpl() {
        Thread broadcast = new Thread(this);
        broadcast.setDaemon(true);
        broadcast.setName("app-events-broadcast-thread");
        broadcast.start();

        ShutDownJob.addShutDownHook(broadcast::interrupt);
    }

    @Override
    public AutoCloseable subscribe(long sinceSeq, EventListener listener) {
        StateEvent peek = storage.peek();
        long earliestSeq = peek == null ? 0 : peek.seq();
        long veryLastSeq = -1;
        if (sinceSeq + 1 < earliestSeq) {
            // GapDetected 不走统一广播 seq 没意义，传 -1
            listener.onEvent(new GapDetected(sinceSeq, earliestSeq, -1));
        } else {
            for (StateEvent event : storage) {
                if (event.seq() > sinceSeq) {
                    listener.onEvent(event);
                }
                veryLastSeq = event.seq();
            }
        }
        long finalVeryLastSeq = veryLastSeq;
        read(() -> keepUpAndAdd(finalVeryLastSeq, listener));
        return () -> listeners.remove(listener);
    }

    private void keepUpAndAdd(long veryLastSeq, EventListener listener) {
        for (StateEvent event : storage) {
            if (event.seq() > veryLastSeq) {
                listener.onEvent(event);
            }
        }
        listeners.add(listener);
    }

    @Override
    public synchronized StateEvent publish(Function<Long, StateEvent> function) {
        if (publishDepth > 0) {
            throw new IllegalStateException("no publish invoke allowed in function");
        }
        publishDepth++;
        try {
            StateEvent event = function.apply(seq);
            if (event == null) {
                return null;
            }
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
            return event;
        } finally {
            publishDepth--;
        }
    }

    @Override
    public synchronized long read(Runnable runnable) {
        runnable.run();
        return seq - 1;
    }

    @Override
    public void run() {
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
                LOG.warn("Interrupted while waiting for event enqueue storage or being took");
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable e) {
                LOG.warn("exception while processing events", e);
            }
        }
    }

    private void makeSpace() {
        for (int i = 0; i < EMPTY_SPACE; i++) {
            storage.poll();
        }
    }
}

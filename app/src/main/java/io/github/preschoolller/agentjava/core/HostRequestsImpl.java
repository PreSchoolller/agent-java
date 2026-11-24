package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.core.interfaces.HostRequests;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

public class HostRequestsImpl implements HostRequests {

    private static final Map<String, Consumer<Answer>> requests = new ConcurrentHashMap<>();

    @Override
    public List<Request> pending() {
        return List.of();
    }

    @Override
    public void answer(String requestId, Answer answer) {

    }

    @Override
    public void cancel(String requestId) {

    }

    public static String makeRequest(Consumer<Answer> function) {
        String requestId = UUID.randomUUID().toString();
        requests.putIfAbsent(requestId, function);
        return requestId;
    }
}

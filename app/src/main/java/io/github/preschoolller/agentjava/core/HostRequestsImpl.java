package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.core.interfaces.HostRequests;

import java.util.List;

public class HostRequestsImpl implements HostRequests {
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
}

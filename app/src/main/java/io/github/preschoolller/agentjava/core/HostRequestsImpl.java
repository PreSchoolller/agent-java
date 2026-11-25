package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.core.events.CloseReason;
import io.github.preschoolller.agentjava.core.events.HostRequestClosed;
import io.github.preschoolller.agentjava.core.events.HostRequestOpened;
import io.github.preschoolller.agentjava.core.interfaces.HostRequests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

public class HostRequestsImpl implements HostRequests, HostRequestRegistry {

    private final Map<String, RequestAnswer> requests = new ConcurrentHashMap<>();

    private static final Logger LOG = LoggerFactory.getLogger(HostRequestsImpl.class);

    private final EventPublisher eventPublisher;

    HostRequestsImpl(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public List<Request> pending() {
        return requests.values().stream()
                .map(RequestAnswer::request)
                .toList();
    }

    @Override
    public void answer(String requestId, Answer answer) {
        RequestAnswer requestAnswer = requests.remove(requestId);
        if (requestAnswer == null) {
            return;
        }
        requestAnswer.answerConsumer.accept(answer);
    }

    @Override
    public void cancelRequest(String requestId) {
        LOG.info("cancel request {} received", requestId);
        RequestAnswer requestAnswer = requests.remove(requestId);
        if (requestAnswer == null) {
            return;
        }
        requestAnswer.answerConsumer.accept(new HostRequests.Cancel("user cancelled"));
    }

    @Override
    public String makeRequest(Consumer<Answer> answerConsumer, Function<String, Request> requestIdForRequest) {
        String requestId = UUID.randomUUID().toString();
        if (requestIdForRequest == null || answerConsumer == null) {
            throw new IllegalArgumentException("at least one parameter is null");
        }
        Request request = requestIdForRequest.apply(requestId);
        if (request == null || !requestId.equals(request.requestId())) {
            throw new IllegalArgumentException("request id not match");
        }
        requests.putIfAbsent(requestId, new RequestAnswer(request, answerConsumer));
        eventPublisher.publish(seq -> new HostRequestOpened(request.sessionId(), request, seq));
        return requestId;
    }

    @Override
    public void drawbackRequest(String requestId, String reason) {
        RequestAnswer requestAnswer = requests.remove(requestId);
        if (requestAnswer == null) {
            LOG.warn("drawbackRequest but request id not found {}", requestId);
            return;
        }
        if (reason != null && !reason.isBlank()) {
            eventPublisher.publish(seq -> new HostRequestClosed(requestId, requestAnswer.request.sessionId(), CloseReason.CANCELLED, seq));
        } else {
            reason = "drawback";
        }
        requestAnswer.answerConsumer.accept(new HostRequests.Cancel(reason));
    }

    record RequestAnswer(Request request, Consumer<Answer> answerConsumer) {}
}

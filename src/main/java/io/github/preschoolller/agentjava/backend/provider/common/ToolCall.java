package io.github.preschoolller.agentjava.backend.provider.common;

import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class ToolCall {

    public static final ToolCall POISON = new ToolCall();

    private volatile Tool tool;

    private volatile String arguments;

    private volatile Status status;

    private volatile String callId;

    private volatile String result;

    private CompletableFuture<Status> statusReady;
    private CompletableFuture<Status> statusFinal;

    public ToolCall() {
        tool = ToolManager.EMPTY_TOOL;
        status = Status.EMPTY;
        statusReady = new CompletableFuture<>();
        statusFinal = new CompletableFuture<>();
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public Tool getTool() {
        return tool;
    }
    public Status getStatus() {
        return status;
    }

    public String getArguments() {
        return arguments;
    }

    public void setStatus(Status status) {
        this.status = status;
        if (status != null && status != Status.EMPTY) {
            statusReady.complete(status);
        }
        if (status == Status.INCOMPLETE || status == Status.COMPLETE) {
            statusFinal.complete(status);
        }
    }

    /**
     * 预期状态流转：EMPTY -> IN_PROGRESS -> COMPLETE/INCOMPLETE
     */
    public Status awaitStatus() throws InterruptedException {
        Status s = getStatus();
        if (s != null && s != Status.EMPTY) {
            return s;
        }
        try {
            if (statusReady.isDone()) {
                return statusFinal.get();
            }
            return statusReady.get();
        } catch (InterruptedException e) {
            System.out.println("awaitStatus interrupted");
            throw e;
        } catch (ExecutionException e) {
            System.out.println("awaitStatus execution error");
            throw new RuntimeException(e);
        }
    }

    public void setTool(Tool tool) {
        this.tool = tool;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public static enum Status {
        EMPTY,
        IN_PROGRESS,
        COMPLETE,
        INCOMPLETE
    }
}

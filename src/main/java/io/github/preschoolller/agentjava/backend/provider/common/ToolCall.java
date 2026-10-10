package io.github.preschoolller.agentjava.backend.provider.common;

import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class ToolCall {

    private volatile Tool tool;

    private volatile String arguments;

    private volatile Status status;

    private volatile String callId;

    private volatile String result;

    private CompletableFuture<Status> statusFinal;

    public ToolCall() {
        tool = ToolManager.EMPTY_TOOL;
        status = Status.EMPTY;
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
        if (status == Status.INCOMPLETE || status == Status.COMPLETE) {
            statusFinal.complete(status);
        }
    }

    public Status awaitFinal() throws InterruptedException {
        Status s = getStatus();
        if (s == Status.COMPLETE || s == Status.INCOMPLETE) {
            return s;
        }
        try {
            return statusFinal.get();
        } catch (InterruptedException e) {
            System.out.println("awaitFinal interrupted");
            throw e;
        } catch (ExecutionException e) {
            System.out.println("awaitFinal execution error");
            throw new RuntimeException(e);
        }
    }

    public void setTool(Tool tool) {
        this.tool = tool;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public enum Status {
        EMPTY,
        IN_PROGRESS,
        COMPLETE,
        INCOMPLETE
    }
}

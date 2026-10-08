package io.github.preschoolller.agentjava.backend.provider.common;

import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

public class ToolCall {

    volatile Tool tool;

    volatile String arguments;

    volatile Status status;

    volatile String callId;

    volatile String result;

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
    }

    public void setTool(Tool tool) {
        this.tool = tool;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public static enum Status {
        IN_PROGRESS,
        COMPLETE,
        INCOMPLETE
    }
}

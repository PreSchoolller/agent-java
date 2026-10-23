package io.github.preschoolller.agentjava.backend.provider.common;

import io.github.preschoolller.agentjava.backend.tool.ToolBash;
import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ToolCall implements Runnable {

    private volatile Tool tool;

    private volatile String arguments;

    private volatile Status status;

    private volatile String callId;

    private volatile String result;

    private volatile String workspace;

    private volatile ToolApprovalGate approvalGate;

    private volatile BackGroundTask backGroundTask;

    private CompletableFuture<Status> statusFinal;

    /** 审批放行、进入执行阶段时完成：awaitFinal 的超时只对执行阶段计费 */
    private CompletableFuture<Void> executionStarted;

    /** 审批放行、进入执行阶段时完成：awaitFinal 的超时只对执行阶段计费 */
    private CompletableFuture<Void> extraGatesDone;

    public ToolCall() {
        tool = ToolManager.EMPTY_TOOL;
        status = Status.EMPTY;
        statusFinal = new CompletableFuture<>();
        executionStarted = new CompletableFuture<>();
        extraGatesDone = new CompletableFuture<>();
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

    /** 初审结束（放行或拒绝）时调用 */
    private void markExecutionStarted() {
        executionStarted.complete(null);
    }

    /** 额外审批结束（放行或拒绝）时调用：等待方从这一刻起开始对执行阶段计超时 */
    private void markExtraGatesDone() {
        extraGatesDone.complete(null);
    }

    /**
     * 等到终态（COMPLETE/INCOMPLETE），分两段：
     * 审批阶段没有时限——等用户点是正常等待，靠审批钩子自己结束（点击 / 停止时看 cancelled 返回 false）；
     * 执行阶段最多等 timeout——工具 I/O 不可中断，卡死时靠超时解锁整轮。
     * 拒绝路径会先 complete executionStarted 再写终态，所以这里不会吊死在第一段。
     */
    public Status awaitFinal(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        Status s = getStatus();
        if (s == Status.COMPLETE || s == Status.INCOMPLETE) {
            return s;
        }
        try {
            executionStarted.get();
        } catch (ExecutionException e) {
            throw new RuntimeException(e); // complete(null) 不会异常完成，防御
        }
        if (tool.needAdditionalApproval()) {
            try {
                extraGatesDone.get();
            } catch (ExecutionException e) {
                throw new RuntimeException(e); // complete(null) 不会异常完成，防御
            }
        }
        try {
            return statusFinal.get(timeout, unit);
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

    public void setApprovalGate(ToolApprovalGate gate) {
        this.approvalGate = gate;
    }

    public void setBackGroundTaskDone(BackGroundTask backGroundTask) {
        this.backGroundTask = backGroundTask;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    @Override
    public void run() {
        // 审批在池线程上做（可阻塞等用户点击）；读流线程只管转发事件，绝不停顿
        if (approvalGate != null && !approvalGate.allow(this, false)) {
            markExecutionStarted(); // 先解锁等待方，紧随其后的终态让它直接返回
            markExtraGatesDone();
            setResult(ToolApprovalGate.DENIED_RESULT);
            setStatus(ToolCall.Status.INCOMPLETE);
            return;
        }
        markExecutionStarted(); // 进入执行阶段
        try {
            String result;
            if (tool.needAdditionalApproval()) {
                result = tool.execute(arguments, workspace, (noMore) -> {
                    if (noMore) {
                        markExtraGatesDone(); // 进入实际执行阶段，awaitFinal 的执行超时从这里起算（审批等待不计入）
                        return true;
                    }
                    return approvalGate != null && approvalGate.allow(this, true);
                });
                markExtraGatesDone();
            } else {
                if (tool instanceof ToolBash toolBash) {
                    result = toolBash.executeEnhance(arguments, workspace, out -> backGroundTask.markDone(this, out));
                } else {
                    result = tool.execute(arguments, workspace);
                }
            }
            setResult(result);
            setStatus(ToolCall.Status.COMPLETE);
        } catch (Exception e) {
            setResult("tool call error, id: " + getCallId() + " with message: " + e.getMessage());
            setStatus(ToolCall.Status.INCOMPLETE);
        } finally {
            markExtraGatesDone();
            markExecutionStarted();
        }
    }

    public enum Status {
        EMPTY,
        IN_PROGRESS,
        COMPLETE,
        INCOMPLETE
    }
}

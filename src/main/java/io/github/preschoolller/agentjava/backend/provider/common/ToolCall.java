package io.github.preschoolller.agentjava.backend.provider.common;

import io.github.preschoolller.agentjava.backend.tool.ToolManager;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.util.concurrent.CompletableFuture;

public class ToolCall implements Runnable {

    private volatile Tool tool;

    private volatile String arguments;

    private volatile String callId;

    private volatile String result;

    private volatile String workspace;

    private volatile ToolApprovalGate approvalGate;

    private volatile BackGroundTask backGroundTask;

    /** 终态（COMPLETE/INCOMPLETE）：completeTerminally 先到先得，唯一事实源 */
    private final CompletableFuture<Status> statusFinal = new CompletableFuture<>();

    /** 初审结束（放行或拒绝）时完成：非额外审批工具的执行超时从这里起算 */
    private final CompletableFuture<Void> executionStarted = new CompletableFuture<>();

    /** 额外审批结束（放行或拒绝）时完成：额外审批工具的执行超时从这里起算（审批等待不计入） */
    private final CompletableFuture<Void> extraGatesDone = new CompletableFuture<>();

    public ToolCall() {
        tool = ToolManager.EMPTY_TOOL;
    }

    public String getResult() {
        return result;
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

    public String getArguments() {
        return arguments;
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

    public void setBackGroundTask(BackGroundTask backGroundTask) {
        this.backGroundTask = backGroundTask;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    /**
     * 写入终态，先到先得：正常完成、异常、拒绝、超时多方竞写时只有第一个生效——
     * 迟到的超时任务不得覆盖真实结果（result 必须在 statusFinal 完成前可见，等待方据此读结果）
     */
    public boolean completeTerminally(Status status, String result) {
        synchronized (statusFinal) {
            if (statusFinal.isDone()) {
                return false;
            }
            this.result = result;
            statusFinal.complete(status);
            return true;
        }
    }

    /** 供外界回调式组合等待的三个 future；执行超时用哪个起点见各字段注释 */
    public ToolCallStatus getCombinedStatus() {
        return new ToolCallStatus(statusFinal, executionStarted, extraGatesDone);
    }

    @Override
    public void run() {
        // 审批在池线程上做（可阻塞等用户点击）；读流线程只管转发事件，绝不停顿。
        // 全程兜底 Throwable：任何路径都必须到达终态，否则续轮的 join 永久挂起
        try {
            if (approvalGate != null && !approvalGate.allow(this, false)) {
                completeTerminally(ToolCall.Status.INCOMPLETE, ToolApprovalGate.DENIED_RESULT);
                return;
            }
            executionStarted.complete(null); // 初审通过，非额外审批工具的执行计时起点
            String result;
            if (tool.supportsAdditionalApproval()) {
                result = tool.execute(arguments, workspace, (noMore) -> {
                    if (noMore) {
                        extraGatesDone.complete(null); // 进入实际执行，额外审批工具的执行计时起点（审批等待不计入）
                        return true;
                    }
                    return approvalGate != null && approvalGate.allow(this, true);
                });
            } else if (tool.supportsBackGroundTask()) {
                result = tool.execute(arguments, workspace, callId, (out -> backGroundTask.markDone(callId, out)));
            } else {
                result = tool.execute(arguments, workspace);
            }
            completeTerminally(ToolCall.Status.COMPLETE, result);
        } catch (Throwable e) {
            completeTerminally(ToolCall.Status.INCOMPLETE,
                    "tool call error, id: " + getCallId() + " with message: " + e.getMessage());
        } finally {
            executionStarted.complete(null);
            extraGatesDone.complete(null);
        }
    }

    public enum Status {
        COMPLETE,
        INCOMPLETE
    }

    /**
     * ToolCall 组合状态，供外界查看
     * @param statusFinal 终态，工具执行完成 COMPLETE/INCOMPLETE
     * @param executionStarted 完成标志开始工具执行，但可能还会申请额外门禁
     * @param extraGatesDone 额外门禁通过，真正开始工具执行超时等待
     */
    public record ToolCallStatus(CompletableFuture<Status> statusFinal, CompletableFuture<Void> executionStarted, CompletableFuture<Void> extraGatesDone) {}
}

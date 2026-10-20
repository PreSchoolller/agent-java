package io.github.preschoolller.agentjava.backend.tool.interfaces;

public interface NeedAdditionalApproval {
    /**
     * 工具额外（无视readonly）发起的审批请求
     * @param noMore 无需再审批，调用后即正式工具执行
     * @return 审批结果
     */
    boolean requestApproval(boolean noMore);
}

package io.github.preschoolller.agentjava.backend.provider.common;

public enum RoleEnum {
    USER,
    TOOL_RESULT,
    ASSISTANT,
    /** 上下文压缩水位线标记（不进请求）：Main 补记给上下文管理插件的窗口末尾，
     *  插件按它识别压缩边界并在返回时剥掉；provider 序列化对它静默跳过——就算劣质
     *  插件原样透传也不会污染请求（见两个 provider 的 switch 分支） */
    CONTEXT_MARKER
}

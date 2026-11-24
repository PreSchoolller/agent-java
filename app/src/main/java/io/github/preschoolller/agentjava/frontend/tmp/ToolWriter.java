package io.github.preschoolller.agentjava.frontend.tmp;

public interface ToolWriter {
    void args(String delta);                                        // 参数增量，只展示不落库

    void executing();                                               // 进入执行阶段

    void finish(String result, boolean success, String arguments);   // 终态：结果落库、卡片翻转

    String getMessageId();
}

package io.github.preschoolller.agentjava.backend.tool.interfaces;

import io.github.preschoolller.agentjava.backend.tool.ToolManager;

import java.util.Map;

public interface Tool {
    /**
     * @return 模型看到并调用的名字
     */
    String name();

    /**
     * @return 给模型看的说明（写清楚何时该用、参数含义）
     */
    String description();

    /**
     * @return 工具所需参数 JSON SCHEMA
     */
    Map<String, Object> schema();

    /**
     * 执行工具
     * @param arguments 模型给的 JSON 字符串
     * @param workspace 本会话绑定的工作区目录
     * （绝对路径，系统提示词里已告知模型）：参数里的相对路径按它解析，绝对路径原样用；
     * 可能为 null（理论上纯聊天不会有工具调用，防御时按默认工作目录处理）
     * @return 纯文本
     */
    default String execute(String arguments, String workspace) {
        return execute(arguments, workspace, null);
    }

    /**
     * 执行工具
     * @param arguments 模型给的 JSON 字符串
     * @param workspace 本会话绑定的工作区目录
     * （绝对路径，系统提示词里已告知模型）：参数里的相对路径按它解析，绝对路径原样用；
     * 可能为 null（理论上纯聊天不会有工具调用，防御时按默认工作目录处理）
     * @param approvalGate 工具可能会发起的计划外审批，无视 readonly，FULL_ACCESS 下依旧会跳过
     * @return 纯文本
     */
    default String execute(String arguments, String workspace, NeedAdditionalApproval approvalGate) {
        return "";
    }

    /**
     * 执行工具
     * @param arguments 模型给的 JSON 字符串
     * @param workspace 本会话绑定的工作区目录
     * （绝对路径，系统提示词里已告知模型）：参数里的相对路径按它解析，绝对路径原样用；
     * 可能为 null（理论上纯聊天不会有工具调用，防御时按默认工作目录处理）
     * @param callId call id
     * @param backGroundTaskDone 工具可能会发起的后台任务完成回调
     * @return 纯文本
     */
    default String execute(String arguments, String workspace, String callId, BackGroundTaskDone backGroundTaskDone) {
        // 未实现四参版本却声明了 supportsBackGroundTask 的工具，退化为前台执行而不是静默返回空结果
        return execute(arguments, workspace);
    }

    /**
     * 只读工具：不写盘、不改外部状态（如读文件）。权限模式据此决定要不要弹确认
     * （见 AgentMode#requiresApproval）。默认按"有副作用"处理——认错了方向是白问一次，
     * 把写操作认成只读是放过去一次，后者代价大得多。
     */
    default boolean readOnly() {
        return false;
    }

    /**
     * 是否可能需要额外的审批，无视 readonly，FULL_ACCESS 下依旧会跳过
     */
    default boolean supportsAdditionalApproval() {
        return false;
    }

    /**
     * 是否可能需要在后台运行
     */
    default boolean supportsBackGroundTask() {
        return false;
    }

    /**
     * 供给 ui 交互提示，搭配 uiInteractive(String) 使用
     * @return ui交互 提示
     */
    default String uiTips() {
        return "not implemented";
    }

    /**
     * 供给 ui 交互，ui 传入用户输入 input，工具可自行实现一定（uiTips描述的）功能，并给予反馈
     * @param input ui 返回的用户输入，可持久化保存，通过方法 getPersistentInput() 获取。
     * @return 给予 ui 的反馈
     */
    default String uiInteractive(String input) {
        return "not implemented";
    }

    /**
     * 不建议重写此方法
     * @return uiInteractive(String input) 中持久化的 input
     */
    default String getPersistentInput() {
        return ToolManager.getPersistentToolInput(this);
    }
}

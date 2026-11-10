package io.github.preschoolller.agentjava.backend.maintenance.interfaces;

import io.github.preschoolller.agentjava.backend.maintenance.PluginManager;

public interface PluginBase {

    /**
     * @return 名字
     */
    default String name() {
        return "not implemented";
    }

    /**
     * @return 介绍
     */
    default String description() {
        return "not implemented";
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
        return PluginManager.getPersistentInput(this);
    }
}

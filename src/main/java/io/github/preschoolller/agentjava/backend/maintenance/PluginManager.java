package io.github.preschoolller.agentjava.backend.maintenance;

import io.github.preschoolller.agentjava.backend.maintenance.interfaces.PluginBase;

public class PluginManager {

    /**
     * ServiceBase.getPersistentInput() 支撑
     * @param serviceBase 插件基础信息
     * @return 持久化的插件信息
     */
    public static String getPersistentInput(PluginBase serviceBase) {
        // 待前端将tool-properties泛化，不用支持老数据。
        return null;
    }
}

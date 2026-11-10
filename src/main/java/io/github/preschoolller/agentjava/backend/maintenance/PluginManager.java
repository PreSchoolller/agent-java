package io.github.preschoolller.agentjava.backend.maintenance;

import io.github.preschoolller.agentjava.backend.maintenance.interfaces.PluginBase;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;

public class PluginManager {

    private static final AppSettings appSettings = AppSettings.get();

    /**
     * ServiceBase.getPersistentInput() 支撑
     * @param serviceBase 插件基础信息
     * @return 持久化的插件信息
     */
    public static String getPersistentInput(PluginBase serviceBase) {
        return appSettings.getPersistentToolInput(serviceBase.name());
    }
}

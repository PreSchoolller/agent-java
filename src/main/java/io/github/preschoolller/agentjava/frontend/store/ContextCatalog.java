package io.github.preschoolller.agentjava.frontend.store;

import io.github.preschoolller.agentjava.backend.context.ContextHandlerManager;
import io.github.preschoolller.agentjava.backend.context.interfaces.ContextHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 上下文处理器注册表读桥：读 ContextHandlerManager 注册的可修改（非 readOnly）处理器清单，
 * 给设置页「上下文」单选用。与 ProviderCatalog 同一模式：读桥包 catch(Throwable)，
 * 拿不到退空列表，调用方给"未拿到列表"提示，不改动任何落盘语义。
 */
public final class ContextCatalog {

    /**
     * 一个单选项：name 是单选存取的键（AppSettings.activeContextHandler），className 给
     * tooltip 排查身份，description 是插件的自我介绍
     */
    public record HandlerInfo(String name, String className, String description) {
    }

    private ContextCatalog() {
    }

    /**
     * 已注册的全部可修改（非 readOnly）上下文处理器，按名字排序；失败退空列表
     */
    public static List<HandlerInfo> registered() {
        List<HandlerInfo> infos = new ArrayList<>();
        try {
            for (ContextHandler handler : new ArrayList<>(ContextHandlerManager.getModifiableHandlers())) {
                infos.add(new HandlerInfo(handler.name(), handler.getClass().getName(), handler.description()));
            }
        } catch (Throwable ignored) {
            // 裸类路径回归/插件类加载失败：按"没拿到列表"兜底（踩坑 #26 同源）
        }
        infos.sort(Comparator.comparing(HandlerInfo::name));
        return List.copyOf(infos);
    }
}

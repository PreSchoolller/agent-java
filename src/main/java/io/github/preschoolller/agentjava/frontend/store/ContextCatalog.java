package io.github.preschoolller.agentjava.frontend.store;

import io.github.preschoolller.agentjava.backend.context.ContextHandlerManager;
import io.github.preschoolller.agentjava.backend.context.interfaces.ContextHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 上下文处理器注册表读桥：读 ContextHandlerManager 注册的处理器清单与 ui 交互通道
 * （uiTips/uiInteractive/getPersistentInput，同 ToolCatalog 模式——持久化键=插件 name()，
 * 后端经 PluginBase.getPersistentInput 消费，前端 setPersistentToolInput 落盘，两端同名）。
 * 与 ProviderCatalog 同一模式：读桥包 catch(Throwable)，拿不到退空列表/null，不抛。
 */
public final class ContextCatalog {

    /**
     * 一个处理器条目：name 是单选存取的键（AppSettings.activeContextHandler），className 给
     * tooltip 排查身份，description 是插件的自我介绍；interactiveTips=uiTips 原文（非接口
     * 默认哨兵才有值，null=不支持交互，前端不渲染交互区）
     */
    public record HandlerInfo(String name, String className, String description, String interactiveTips) {
    }

    private ContextCatalog() {
    }

    /**
     * 已注册的全部可修改（非 readOnly）上下文处理器，按名字排序；失败退空列表
     */
    public static List<HandlerInfo> modifiable() {
        return catalog(ContextHandlerManager.getModifiableHandlers());
    }

    /**
     * 已注册的全部只读上下文处理器，按名字排序；只读处理器不可选择，展示用（记录行为始终开启）
     */
    public static List<HandlerInfo> readOnly() {
        return catalog(ContextHandlerManager.getReadOnlyHandlers());
    }

    /** 通用装配：逐个自省（单个插件自省失败不连坐整表），名字排序；registry 读取本身失败退空 */
    private static List<HandlerInfo> catalog(List<ContextHandler> registry) {
        List<HandlerInfo> infos = new ArrayList<>();
        try {
            for (ContextHandler handler : new ArrayList<>(registry)) {
                String name;
                String description;
                String tips;
                try {
                    name = handler.name();
                    description = handler.description();
                    String rawTips = handler.uiTips();
                    // 接口默认哨兵=没实现交互；null 让前端不渲染交互控件
                    tips = "not implemented".equals(rawTips) ? null : rawTips;
                } catch (Throwable ignored) {
                    // 单个插件自省失败不连坐整个注册表（同 ToolCatalog 的逐条防御）
                    continue;
                }
                infos.add(new HandlerInfo(name, handler.getClass().getName(), description, tips));
            }
        } catch (Throwable ignored) {
            // 裸类路径回归/插件类加载失败：按"没拿到列表"兜底（踩坑 #26 同源）
        }
        infos.sort(Comparator.comparing(HandlerInfo::name));
        return List.copyOf(infos);
    }

    /**
     * 读处理器的持久化交互输入（PluginBase.getPersistentInput 读桥，前端预填交互区输入框用）：
     * null=未配置或插件不可用（裸类路径回归等，踩坑 #26 同源，兜底不抛）
     */
    public static String persistentInput(String handlerName) {
        try {
            for (ContextHandler handler : new ArrayList<>(ContextHandlerManager.getModifiableHandlers())) {
                if (handlerName.equals(handler.name())) {
                    return handler.getPersistentInput();
                }
            }
            for (ContextHandler handler : new ArrayList<>(ContextHandlerManager.getReadOnlyHandlers())) {
                if (handlerName.equals(handler.name())) {
                    return handler.getPersistentInput();
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 执行处理器的交互（PluginBase.uiInteractive 写桥）：把用户输入交给插件自行解释并应用，
     * 返回反馈文本；插件不可用/调用失败退 null（前端按"未执行成功"处理，不落盘）。
     * 可能拉起进程，调用方必须在后台线程跑，不能占 EDT。
     */
    public static String uiInteractive(String handlerName, String input) {
        try {
            for (ContextHandler handler : new ArrayList<>(ContextHandlerManager.getModifiableHandlers())) {
                if (handlerName.equals(handler.name())) {
                    return handler.uiInteractive(input);
                }
            }
            for (ContextHandler handler : new ArrayList<>(ContextHandlerManager.getReadOnlyHandlers())) {
                if (handlerName.equals(handler.name())) {
                    return handler.uiInteractive(input);
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}

package io.github.preschoolller.agentjava.frontend.store;

import io.github.preschoolller.agentjava.backend.provider.ProviderFactory;
import io.github.preschoolller.agentjava.backend.provider.interfaces.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provider 注册表读桥：读 ProviderFactory 按 API 类型分的注册池，给设置页 Provider 单选用。
 * 与 ToolCatalog 同一模式：读桥包 catch(Throwable)，注册池拿不到（类加载失败/裸类路径回归）
 * 退空列表，调用方给"未拿到列表"提示，不改动任何落盘语义。
 */
public final class ProviderCatalog {

    /** 一个单选项：name 是单选存取的键（Profile.activeProviderName），className 给 tooltip 排查身份 */
    public record ProviderInfo(String name, String className) {}

    private ProviderCatalog() {
    }

    /** 指定 API 类型（Profile.API_* 常量）下注册的全部 provider，按名字排序；失败退空列表 */
    public static List<ProviderInfo> registered(String apiType) {
        List<ProviderInfo> infos = new ArrayList<>();
        try {
            Map<String, Provider> pool = ProviderFactory.providersOf(ProviderFactory.apiOf(apiType));
            List<String> names = new ArrayList<>(pool.keySet());
            names.sort(String::compareTo);
            for (String name : names) {
                infos.add(new ProviderInfo(name, pool.get(name).getClass().getName()));
            }
        } catch (Throwable ignored) {
            // ProviderFactory.<clinit> 或插件类加载失败：按"没拿到列表"兜底（同 ToolCatalog）
        }
        return List.copyOf(infos);
    }
}

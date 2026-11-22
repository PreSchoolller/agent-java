package io.github.preschoolller.agentjava.api;

import java.util.Objects;

/**
 * {@link AgentJavaSessionProvider} 的静态门面：插件侧经它调用宿主会话能力，
 * 宿主在启动装配阶段调用 {@link #install} 注入实现。
 *
 * <p>与 spi 根包的 PluginPersistentHelper / PluginShutDownJobHelper / ToolHelper 同一范式：
 * 插件只认接口，不认宿主实现类，因此插件模块可以只依赖 spi 编译。
 */
public final class AgentJavaSessions {

    private static volatile AgentJavaSessionProvider provider;

    private AgentJavaSessions() {
    }

    /**
     * 注入宿主实现。宿主启动早期调用一次；重复调用以最后一次为准（测试可借此替换）。
     *
     * @param newProvider 宿主实现，不可为 null——装配错误应该在启动时炸出来，
     *                    而不是等到插件调用时静默失败
     */
    public static void install(AgentJavaSessionProvider newProvider) {
        provider = Objects.requireNonNull(newProvider, "AgentJavaSessionProvider 不能为 null");
    }

    /**
     * 取当前宿主实现。
     *
     * @throws IllegalStateException 宿主尚未装配时抛出，提示去查启动装配流程
     */
    public static AgentJavaSessionProvider provider() {
        AgentJavaSessionProvider current = provider;
        if (current == null) {
            throw new IllegalStateException(
                    "AgentJavaSessionProvider 尚未装配：宿主启动时应调用 AgentJavaSessions.install(...)");
        }
        return current;
    }
}

package io.github.preschoolller.agentjava.spi;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 自己实现的简易 ShutDownHook ，在日志或者其他组件关闭前调用。
 */
public class PluginShutDownJobHelper {

    private static volatile Function<Consumer<Void>, Boolean> addShutDownJob = consumer -> false;
    private static volatile Function<Consumer<Void>, Boolean> removeShutDownJob = consumer -> false;


    public static void injectAdd(Function<Consumer<Void>, Boolean> function) {
        if (function == null) {
            return;
        }
        addShutDownJob = function;
    }

    public static void injectRemove(Function<Consumer<Void>, Boolean> function) {
        if (function == null) {
            return;
        }
        removeShutDownJob = function;
    }

    public static boolean addShutDownHook(Consumer<Void> hook) {
        return addShutDownJob.apply(hook);
    }

    public static boolean removeShutDownHook(Consumer<Void> hook) {
        return removeShutDownJob.apply(hook);
    }
}

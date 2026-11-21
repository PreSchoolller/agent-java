package io.github.preschoolller.agentjava.spi;

import java.util.function.Function;

/**
 * 自己实现的简易 ShutDownHook ，在日志或者其他组件关闭前调用。
 */
public class PluginShutDownJobHelper {

    private static volatile Function<Runnable, Boolean> addShutDownJob = consumer -> false;
    private static volatile Function<Runnable, Boolean> removeShutDownJob = consumer -> false;


    public static void inject(Function<Runnable, Boolean> add, Function<Runnable, Boolean> remove) {
        if (add == null) {
            return;
        }
        addShutDownJob = add;

        if (remove == null) {
            return;
        }
        removeShutDownJob = remove;
    }

    public static boolean addShutDownHook(Runnable hook) {
        return addShutDownJob.apply(hook);
    }

    public static boolean removeShutDownHook(Runnable hook) {
        return removeShutDownJob.apply(hook);
    }
}

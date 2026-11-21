package io.github.preschoolller.agentjava.spi;

import io.github.preschoolller.agentjava.spi.tool.records.ToolInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.BiFunction;

public class ToolHelper {

    private static volatile BiFunction<Collection<String>, Collection<String>, Collection <ToolInfo>> shouldRevealTools =
            (history, enabled) -> new ArrayList<>();

    public static void inject(BiFunction<Collection<String>, Collection<String>, Collection <ToolInfo>> function) {
        if (function == null) {
            return;
        }
        shouldRevealTools = function;
    }

    public static Collection<ToolInfo> shouldRevealTools(Collection<String> historyTools, Collection<String> enabledTools) {
        return shouldRevealTools.apply(historyTools, enabledTools);
    }
}

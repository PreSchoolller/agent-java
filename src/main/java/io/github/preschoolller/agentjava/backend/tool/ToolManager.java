package io.github.preschoolller.agentjava.backend.tool;

import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.provider.common.ToolCall;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class ToolManager {
    public static final Tool BAD_TOOL = BadTool.getInstance();
    public static final Tool EMPTY_TOOL = EmptyTool.getInstance();
    public static final Map<String, Tool> tools = new ConcurrentHashMap<>(Map.of(
            ToolRead.getInstance().name(), ToolRead.getInstance(),
            ToolBash.getInstance().name(), ToolBash.getInstance()
    ));

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ToolManager.class);

    private ToolManager() {
    }

    /**
     * 允许插件覆盖同名内部工具，便于增强
     * @param tool 工具
     */
    public static void register(Tool tool) {
        if (tool == null || tool.name() == null) {
            return;
        }
        Tool preValue = tools.put(tool.name(), tool);
        if (preValue != null) {
            LOG.info("tool {} was exchanged", tool.name());
        }
    }

    public static Tool findByName(String name) {
        return tools.getOrDefault(name, BAD_TOOL);
    }

    /** 只接受已注册的 Tool（buildParams 遍历 tools 时用）；BAD_TOOL.schema() 是 null，别拿占位工具进来 */
    public static FunctionTool getFunctionTool(Tool tool) {
        HashMap<String, JsonValue> parameters = new HashMap<>();
        if (tool.schema() != null) {
            tool.schema().forEach((k,v) -> parameters.put(k, JsonValue.from(v)));
        }
        parameters.put("additionalProperties", JsonValue.from(false)); // strict 模式硬性要求；required 全集暂未补（当前端点不校验），要兼容官方端点时再补
        FunctionTool functionTool = FunctionTool
                .builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(FunctionTool.Parameters.builder().additionalProperties(parameters).build())
                .strict(true)
                .build();
        return functionTool;
    }

    /** 只接受已注册的 Tool（buildParams 遍历 tools 时用）；BAD_TOOL.schema() 是 null，别拿占位工具进来 */
    public static ChatCompletionFunctionTool getChatCompletionFunctionTool(Tool tool) {
        HashMap<String, JsonValue> parameters = new HashMap<>();
        if (tool.schema() != null) {
            tool.schema().forEach((k,v) -> parameters.put(k, JsonValue.from(v)));
        }
        parameters.put("additionalProperties", JsonValue.from(false)); // 同上：strict 模式硬性要求
        return ChatCompletionFunctionTool
                .builder()
                .function(FunctionDefinition.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .parameters(FunctionParameters.builder()
                                .putAllAdditionalProperties(parameters)
                                .build())
                        .strict(true)
                        .build())
                .build();
    }

    /** 执行一次工具调用：workspace 是会话绑定的工作区目录，透传给工具做相对路径解析 */
    public static String execute(ResponseFunctionToolCall functionTool, String workspace) {
        return findByName(functionTool.name()).execute(functionTool.arguments(), workspace);
    }

    /** 执行一次工具调用：workspace 是会话绑定的工作区目录，透传给工具做相对路径解析 */
    public static String execute(ToolCall toolCall, String workspace) {
        if (toolCall == null || toolCall.getTool() == null || toolCall.getTool() instanceof BadTool) {
            return BAD_TOOL.execute("", workspace);
        }
        return toolCall.getTool().execute(toolCall.getArguments(), workspace);
    }

    public static void loadExternal() {
        try {
            Files.createDirectories(Constants.PLUGIN_DIR);
        } catch (IOException e) {
            LOG.error("...-_-!...");
        }
        try (Stream<Path> files = Files.find(Constants.PLUGIN_DIR, 1,
                (path, basicFileAttributes) -> path.getFileName().toString().endsWith(".jar"))) {
            Iterator<Path> iterator = files.iterator();
            while (iterator.hasNext()) {
                Path jar = iterator.next();
                String jarNameWithSuffix = jar.getFileName().toString();
                String jarName = jarNameWithSuffix.substring(0, jarNameWithSuffix.length() - ".jar".length());
                AtomicInteger count = new AtomicInteger();

                try {
                    URL[] jarUrl = new URL[]{jar.toUri().toURL()};
                    URLClassLoader urlClassLoader = new URLClassLoader("plugin-" + jarName, jarUrl, ToolManager.class.getClassLoader());
                    ServiceLoader.load(Tool.class, urlClassLoader).forEach(tool -> {
                        register(tool);
                        LOG.info("{} loaded by {}", tool.getClass(), tool.getClass().getClassLoader());
                        count.incrementAndGet();
                    });
                } catch (MalformedURLException e) {
                    LOG.error("should not be here, riiiight ?");
                } catch (Throwable throwable) {
                    LOG.error("plugin {} failed", jarNameWithSuffix, throwable);
                } finally {
                    LOG.info("plugin {} loaded {} tools", jarNameWithSuffix, count.get());
                }
            }
        } catch (IOException e) {
            LOG.error("load plugin error for: {}", e.getMessage());
        }
    }

    /**
     * 目前规划：
     * 1. 历史中用过的工具不受前端未勾选影响
     * 2. enabled 为 null，默认全部；为空，则不返回结果（历史非空不受影响）
     * @param history 历史中用到的工具名称
     * @param enabled 前端勾选的工具名称
     * @return 应当拼接进请求的工具列表
     */
    public static Collection<Tool> shouldRevealTools(Collection<String> history, Collection<String> enabled) {
        if (enabled == null) {
            return Collections.unmodifiableCollection(tools.values());
        }
        Set<String> names = new HashSet<>(enabled);
        names.addAll(history);
        List<Tool> result = new ArrayList<>();
        for (String name : names) {
            Tool tool = findByName(name);
            if (tool instanceof BadTool) {
                continue;
            }
            result.add(tool);
        }
        return Collections.unmodifiableList(result);
    }
}

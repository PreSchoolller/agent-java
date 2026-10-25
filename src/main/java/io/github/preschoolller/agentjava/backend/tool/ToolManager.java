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
import org.tinylog.Logger;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
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
            Logger.info("tool {} was exchanged", tool.name());
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
            Logger.error("...-_-!...");
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
                        Logger.info("{} loaded by {}", tool.getClass(), tool.getClass().getClassLoader());
                        count.incrementAndGet();
                    });
                } catch (MalformedURLException e) {
                    Logger.error("should not be here, riiiight ?");
                } catch (Throwable throwable) {
                    Logger.error(throwable, "plugin {} failed", jarNameWithSuffix);
                } finally {
                    Logger.info("plugin {} loaded {} tools", jarNameWithSuffix, count.get());
                }
            }
        } catch (IOException e) {
            Logger.error("load plugin error for: {}", e.getMessage());
        }
    }
}

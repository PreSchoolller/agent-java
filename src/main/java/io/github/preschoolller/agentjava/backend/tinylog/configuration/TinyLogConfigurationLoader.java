package io.github.preschoolller.agentjava.backend.tinylog.configuration;

import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.frontend.store.AppSettings;
import org.tinylog.configuration.ConfigurationLoader;

import java.nio.file.Path;
import java.util.Properties;

/**
 * tinylog 配置装载器（SPI 注册，见 META-INF/services）：tinylog 启动时读一次，把
 * AppSettings 里 log.* 自定义键映射成 tinylog 官方属性。落盘键是应用自己的语义
 * （控制台块 + 文件块），与具体日志后端解耦——以后换后端只改这一处映射，设置页与落盘格式不动。
 *
 * <p>映射规则：控制台 → writer1=console（等级/格式可配）；文件 → writer2：不配单文件上限用
 * file writer（append 可配），配了上限用 rolling file writer（size 策略 + backups，文件名
 * 自动补 {count} 占位符，否则滚动各代互相覆盖）；两路全关 → level=off 整体静默（否则
 * tinylog 默认向控制台全量输出）。</p>
 */
public class TinyLogConfigurationLoader implements ConfigurationLoader {

    /** 文件路径未配置时的默认日志文件：~/.agentjava/logs/agent.log */
    private static final String DEFAULT_LOG_FILE =
            Constants.APP_USER_DIR.resolve("logs").resolve("agent.log").toString();

    @Override
    public Properties load() {
        return load(AppSettings.get());
    }

    /** 映射逻辑本体独立成方法：测试用 AppSettings.at(临时文件) 直喂，不碰真实配置 */
    public Properties load(AppSettings settings) {
        Properties properties = new Properties();
        int writer = 0;
        if (settings.isLogConsoleEnabled()) {
            String prefix = "writer" + (++writer);
            properties.put(prefix, "console");
            properties.put(prefix + ".level", settings.getLogConsoleLevel());
            if (notBlank(settings.getLogConsoleFormat())) {
                properties.put(prefix + ".format", settings.getLogConsoleFormat());
            }
        }
        if (settings.isLogFileEnabled()) {
            String prefix = "writer" + (++writer);
            String maxSize = settings.getLogFileMaxSize();
            String path = notBlank(settings.getLogFilePath()) ? settings.getLogFilePath() : DEFAULT_LOG_FILE;
            if (notBlank(maxSize)) {
                properties.put(prefix, "rolling file");
                properties.put(prefix + ".file", withCountPlaceholder(path));
                properties.put(prefix + ".policies", "size: " + maxSize);
                properties.put(prefix + ".backups", String.valueOf(settings.getLogFileBackups()));
            } else {
                properties.put(prefix, "file");
                properties.put(prefix + ".file", path);
                properties.put(prefix + ".append", String.valueOf(settings.isLogFileAppend()));
            }
            properties.put(prefix + ".level", settings.getLogFileLevel());
            if (notBlank(settings.getLogFileFormat())) {
                properties.put(prefix + ".format", settings.getLogFileFormat());
            }
        }
        if (writer == 0) {
            properties.put("level", "off"); // 两路全关：静默运行
        }
        return properties;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 轮转文件名必须有 {count} 占位符（tinylog 滚动文件靠它分代）：缺就插进扩展名前
     * （无扩展名/隐藏文件追加到尾部）。"logs/agent.log" → "logs/agent.{count}.log"，
     * "logs/agent" → "logs/agent.{count}"，已含 {count} 原样返回。
     */
    static String withCountPlaceholder(String path) {
        if (path.contains("{count}")) {
            return path;
        }
        String fileName = Path.of(path).getFileName().toString();
        int base = path.length() - fileName.length(); // 目录前缀长度
        int dot = fileName.lastIndexOf('.');
        int insert = dot <= 0 ? path.length() : base + dot; // ".log" 这种隐藏文件当无扩展名
        return path.substring(0, insert) + ".{count}" + path.substring(insert);
    }
}

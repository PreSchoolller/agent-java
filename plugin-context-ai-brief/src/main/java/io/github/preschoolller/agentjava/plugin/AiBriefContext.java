package io.github.preschoolller.agentjava.plugin;

import io.github.preschoolller.agentjava.backend.context.interfaces.ContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiBriefContext implements ContextHandler {

    private static volatile int threshold;
    private static final Logger LOG = LoggerFactory.getLogger(AiBriefContext.class);

    public AiBriefContext() {
    }

    @Override
    public String name() {
        return "ai-brief";
    }

    @Override
    public String description() {
        return """
                达到目标阈值后，向模型索要上下文摘要来达到压缩上下文的目的。
                """;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String uiTips() {
        return """
                设置上下文压缩阈值[0-100]只接受纯数字正整数。
                空/空白 内容回显当前阈值
                """;
    }

    @Override
    public String uiInteractive(String input) {
        if (input == null || input.isBlank()) {
            return "当前压缩阈值：" + threshold;
        }
        int newThreshold = -1;
        try {
            newThreshold = Integer.parseInt(input.strip());
        } catch (NumberFormatException e) {
            LOG.info("failed to parse input threshold string to int: {}", input);
        }
        if (newThreshold < 0 || newThreshold > 100) {
            LOG.info("input threshold exceeds range: {}", newThreshold);
            return "格式错误，只接受[0-100]纯数字正整数";
        }
        synchronized (AiBriefContext.class) {
            threshold = newThreshold;
        }
        return "重设阈值成功当前阈值：" + threshold;
    }

    @Override
    public ContextManaged handle(ContextDetail contextDetail) {
        LOG.info("handling 啦啦啦啦啦啦啦啊啦啦啦啦啦啦占位测试可达");
        return new ContextManaged(contextDetail.roleMessages());
    }
}

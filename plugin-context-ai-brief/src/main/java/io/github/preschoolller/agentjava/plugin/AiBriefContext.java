package io.github.preschoolller.agentjava.plugin;

import io.github.preschoolller.agentjava.backend.context.interfaces.ContextHandler;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.RoleEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class AiBriefContext implements ContextHandler {

    private static volatile int threshold;
    private static final Logger LOG = LoggerFactory.getLogger(AiBriefContext.class);

    public AiBriefContext() {
        String input = getPersistentInput();
        if (input == null || input.isBlank()) {
            return;
        }
        try {
            int i = Integer.parseInt(input.strip());
            if (i == 0 || (i >= 5 && i <= 9)) {
                threshold = i;
            } else {
                LOG.warn("wrong number of persistent input of AiBriefContext: {}", input);
            }
        } catch (NumberFormatException e) {
            LOG.warn("failed to parse persistent input of AiBriefContext: {}", input);
        }
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
                设置上下文压缩阈值，只接受 0，5，6，7，8，9
                空/空白 内容回显当前阈值
                0 表示不压缩
                5到9之间的任意数x表示阈值为x除以10;
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
        if (newThreshold < 0 || newThreshold > 9 || (newThreshold > 0 && newThreshold < 5)) {
            LOG.info("input threshold exceeds range: {}", newThreshold);
            return "格式错误，只接受[0|5-9]纯数字";
        }
        synchronized (AiBriefContext.class) {
            threshold = newThreshold;
        }
        return "重设阈值成功当前阈值：" + threshold;
    }

    @Override
    public ContextManaged handle(ContextDetail contextDetail) {
        List<PromptParams.RoleMessage> roleMessages = contextDetail.roleMessages();
        if (RoleEnum.CONTEXT_MARKER.equals(roleMessages.get(roleMessages.size() - 1).role())) {
            // last message is context marker, do compress this round
            ArrayList<PromptParams.RoleMessage> result = new ArrayList<>(roleMessages);
            result.remove(result.size() - 1);
            String compressPrompt = """
                    上下文将要达到极限，本轮对话将要进行上下文压缩，总结并返回上下文摘要，以便快速完成任务续接，以下是几个要点。
                    1. 以“使用过的文件”为标题，列出使用过的文件名称。
                    2. 以“使用过的命令”为标题，列出命令目的及结果摘要。
                    3. 以“用户明确要遵循的规则”为标题，列出用户明确要求agent助手遵循的规则，以及隐含（自由决定）的规则摘要。
                    4. 以“当前目标”为标题，列出当前工作内容摘要。
                    """;
            result.add(new PromptParams.RoleMessage(RoleEnum.USER, compressPrompt));
            return new ContextManaged(result);
        } else {
            return new ContextManaged(roleMessages);
        }
    }

    @Override
    public boolean needCompress(ContextDetail contextDetail) {
        if (contextDetail.ctxWindow() == 0 || threshold == 0) {
            return false;
        }
        return contextDetail.ctxWindow() * threshold <= contextDetail.inputTokens() * 10;
    }
}

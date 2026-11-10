package io.github.preschoolller.agentjava.backend.context;

import io.github.preschoolller.agentjava.backend.context.interfaces.ContextHandler;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ContextHandlerManager {

    private static final Logger LOG = LoggerFactory.getLogger(ContextHandlerManager.class);

    private ContextHandlerManager() {
    }

    private static final List<ContextHandler> readOnlyHandlers = new CopyOnWriteArrayList<>();
    private static final List<ContextHandler> modifiableHandlers = new CopyOnWriteArrayList<>();

    public static synchronized void register(Object object) {
        if (!(object instanceof ContextHandler handler)) {
            return;
        }
        if (handler.readOnly()) {
            readOnlyHandlers.add(handler);
            return;
        }
        modifiableHandlers.add(handler);
    }

    public static ContextHandler.ContextManaged handle(ContextHandler.ContextDetail contextDetail) {
        List<PromptParams.RoleMessage> roleMessages = contextDetail.roleMessages();
        ContextHandler.ContextDetail snapShot = new ContextHandler.ContextDetail(Collections.unmodifiableList(roleMessages),
                contextDetail.inputTokens(), contextDetail.outputTokens(), contextDetail.ctxWindow(), contextDetail.mHandlerName());
        for (ContextHandler contextHandler : readOnlyHandlers) {
            contextHandler.handle(snapShot);
        }
        if (contextDetail.mHandlerName() != null) {
            for (ContextHandler modifiableHandler : modifiableHandlers) {
                // 常量在前的写法：劣质插件 name() 返回 null 也不 NPE（EmptyTool/BadTool 就有 return null 的先例）
                if (contextDetail.mHandlerName().equals(modifiableHandler.name())) {
                    return modifiableHandler.handle(snapShot);
                }
            }
            // 配置的名字当前没有注册（插件被卸载/改名）：回落消息原样发出，与前端"回落不启用"语义一致
            LOG.warn("configured modifiable context handler not found: {}, messages sent as-is", contextDetail.mHandlerName());
        }
        return new ContextHandler.ContextManaged(roleMessages);
    }

    public static List<ContextHandler> getReadOnlyHandlers() {
        return Collections.unmodifiableList(readOnlyHandlers);
    }

    public static List<ContextHandler> getModifiableHandlers() {
        return Collections.unmodifiableList(modifiableHandlers);
    }
}

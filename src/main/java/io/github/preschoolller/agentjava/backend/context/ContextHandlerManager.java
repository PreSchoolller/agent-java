package io.github.preschoolller.agentjava.backend.context;

import io.github.preschoolller.agentjava.backend.context.interfaces.ContextHandler;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ContextHandlerManager {

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
                contextDetail.inputTokens(), contextDetail.outputTokens(), contextDetail.ctxWindow(), contextDetail.mHandleName());
        for (ContextHandler contextHandler : readOnlyHandlers) {
            contextHandler.handle(snapShot);
        }
        if (contextDetail.mHandleName() != null) {
            for (ContextHandler modifiableHandler : modifiableHandlers) {
                if (modifiableHandler.name().equals(contextDetail.mHandleName())) {
                    return modifiableHandler.handle(snapShot);
                }
            }
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

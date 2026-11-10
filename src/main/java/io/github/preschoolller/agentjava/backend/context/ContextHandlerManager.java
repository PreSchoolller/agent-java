package io.github.preschoolller.agentjava.backend.context;

import io.github.preschoolller.agentjava.backend.context.interfaces.ContextHandler;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class ContextHandlerManager {

    private ContextHandlerManager() {
    }

    private static final List<ContextHandler> readOnlyHandlers = new ArrayList<>();
    private static final List<ContextHandler> modifiableHandlers = new ArrayList<>();

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
                contextDetail.inputTokens(), contextDetail.outputTokens(), contextDetail.ctxWindow());
        for (ContextHandler contextHandler : new ArrayList<>(readOnlyHandlers)) {
            contextHandler.handle(snapShot);
        }
        Optional<ContextHandler> contextHandler = getModifiableHandlers();
        if (contextHandler.isPresent()) {
            return contextHandler.get().handle(snapShot);
        }
        return new ContextHandler.ContextManaged(roleMessages);
    }

    // 待设计，需要与前端一起设计页面。
    private static Optional<ContextHandler> getModifiableHandlers() {
        return modifiableHandlers.stream().findFirst();
    }
}

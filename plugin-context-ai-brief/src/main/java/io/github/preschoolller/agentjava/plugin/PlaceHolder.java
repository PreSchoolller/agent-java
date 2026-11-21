package io.github.preschoolller.agentjava.plugin;

import io.github.preschoolller.agentjava.backend.context.interfaces.ContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlaceHolder implements ContextHandler {

    private static volatile int threshold;
    private static final Logger LOG = LoggerFactory.getLogger(PlaceHolder.class);

    public PlaceHolder() {
    }

    @Override
    public String name() {
        return "placeholder";
    }

    @Override
    public String description() {
        return """
                place-holder...
                """;
    }

    @Override
    public String uiTips() {
        return """
                空空如也
                """;
    }

    @Override
    public String uiInteractive(String input) {
        return "占位展示";
    }

    @Override
    public ContextManaged handle(ContextDetail contextDetail) {
        LOG.info("do enter here");
        return null;
    }
}

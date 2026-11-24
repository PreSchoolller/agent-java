package io.github.preschoolller.agentjava.frontend.tmp;

public interface AiWriter {
    void chunk(String text);

    void thinking(String text);

    void finishThinking();

    void finish();
}

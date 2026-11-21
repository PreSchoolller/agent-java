package io.github.preschoolller.agentjava.backend.provider.interfaces;

import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderBlockingTextResponse;
import io.github.preschoolller.agentjava.backend.provider.common.ProviderStreamingResponse;

public interface Provider {
    enum Api {
        RESPONSES,
        CHAT_COMPLETION,
        MESSAGES
    }

    /**
     * @return 插件名称，用作全局唯一标识
     */
    default String name() {
        throw new UnsupportedOperationException("must implement this method");
    }

    /**
     * @return 实现的api类型，用于api查找，必须覆写
     */
    default Api implement() {
        throw new UnsupportedOperationException("must implement this method");
    }

    ProviderBlockingTextResponse sendPrompt(PromptParams promptParams);

    ProviderStreamingResponse sendPromptStreaming(PromptParams promptParams);
}

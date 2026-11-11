package io.github.preschoolller.agentjava.backend.context.interfaces;

import io.github.preschoolller.agentjava.backend.maintenance.interfaces.PluginBase;
import io.github.preschoolller.agentjava.backend.provider.common.PromptParams;

import java.util.List;

public interface ContextHandler extends PluginBase {

    @Override
    String name();

    @Override
    String description();

    record ContextDetail(List<PromptParams.RoleMessage> roleMessages, long inputTokens, long outputTokens, long ctxWindow, String mHandlerName) {
    }

    record ContextManaged(List<PromptParams.RoleMessage> roleMessages) {
    }

    /**
     * @return 此handler是否只读，只读返回值会被忽略，非只读按 name() 选择启用（前端设置页单选）；
     */
    default boolean readOnly() {
        return true;
    }

    /**
     * 对上下文进行记录（readOnly）或操作（返回值会传入后端api）
     * @param contextDetail 上下文信息
     * @return 修改后的上下文
     */
    ContextManaged handle(ContextDetail contextDetail);

    /**
     * @param contextDetail 上下文信息
     * @return 是否要进行压缩
     */
    default boolean needCompress(ContextDetail contextDetail) {
        return false;
    }
}

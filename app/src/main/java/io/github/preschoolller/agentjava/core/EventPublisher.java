package io.github.preschoolller.agentjava.core;

import io.github.preschoolller.agentjava.core.events.StateEvent;

import java.util.function.Function;

public interface EventPublisher {

    /**
     * 为保证事件与持久化一致性，建议将落盘行为在 function 中进行
     * 暂不支持在 function 中嵌套调用本方法
     *
     * @param function 给定 seq，返回 StateEvent
     * @return 构造出来的事件，如果为 null，不会发送
     * @throws IllegalArgumentException 当 StateEvent 对象内 seq 与给定值不一致
     * @throws IllegalStateException 当 function 中又调用了 publish
     */
    StateEvent publish(Function<Long, StateEvent> function);

    /**
     * 为保证广播事件与持久化一致性，在这里执行持久化读取
     * @param runnable 读取持久化内容
     * @return 下最后一个已发出事件的 seq
     */
    long read(Runnable runnable);
}

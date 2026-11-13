package io.github.preschoolller.agentjava.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** alive() 的语义边界；pid 文件读写在真实 ~/.agentjava 上，不在这测（避免碰用户现场） */
class WhaleHostTest {

    @Test
    void aliveSemantics() {
        assertFalse(WhaleHost.alive(0), "pid<=0 一律不活");
        assertFalse(WhaleHost.alive(-1), "pid<=0 一律不活");
        assertTrue(WhaleHost.alive(ProcessHandle.current().pid()), "当前 JVM 自己必然活着");
    }
}

package io.github.preschoolller.agentjava.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** alive() 的语义边界；pid 文件读写在真实 ~/.agentjava 上，不在这测（避免碰用户现场） */
class WhaleHostTest {

    @Test
    void aliveSemantics() {
        assertFalse(WhaleHost.alive(0), "pid<=0 一律不活");
        assertFalse(WhaleHost.alive(-1), "pid<=0 一律不活");
        assertTrue(WhaleHost.alive(ProcessHandle.current().pid()), "当前 JVM 自己必然活着");
    }

    @Test
    void bundledWhaleExtractsCompletely(@TempDir Path tmp) throws IOException {
        // 构建期打包（pom 的 bundle-whale，源码不在仓库里）缺源时资源不存在——跳过
        assumeTrue(WhaleHost.class.getResource("/whale/package.json") != null,
                "构建时未打包 whale，跳过解压用例");
        // 开发期 classpath 是 target/classes 目录形态（资源枚举走 Files.walk 分支）
        WhaleHost.extractBundledWhale(tmp.resolve("whale"));
        Path root = tmp.resolve("whale");
        // 宿主侧 + 工具链 + npm 依赖三样齐全，shim 才能在这份副本上跑起来
        assertTrue(Files.isRegularFile(root.resolve("lib").resolve("index.js")), "宿主入口");
        assertTrue(Files.isRegularFile(root.resolve("tools").resolve("bili.mjs")), "工具链入口");
        assertTrue(Files.isRegularFile(root.resolve("node_modules").resolve("qrcode")
                .resolve("package.json")), "qrcode 依赖（lib/index.js 顶层导入 auth.js → qrcode）");
        assertTrue(Files.isRegularFile(root.resolve("node_modules").resolve("dijkstrajs")
                .resolve("package.json")), "qrcode 传递依赖");
        assertTrue(Files.isRegularFile(root.resolve("node_modules").resolve("pngjs")
                .resolve("package.json")), "qrcode 传递依赖");
    }

    @Test
    void bundledExtractionSkipsWhenVersionUnchanged(@TempDir Path tmp) throws IOException {
        assumeTrue(WhaleHost.class.getResource("/whale/package.json") != null,
                "构建时未打包 whale，跳过解压用例");
        Path root = tmp.resolve("whale");
        WhaleHost.extractBundledWhale(root);
        // 改一个已解压文件的时间戳与内容印记，再抽一次：版本一致应整体跳过（印记保留）
        Path probe = root.resolve("lib").resolve("index.js");
        Files.writeString(probe, "// touched\n" + Files.readString(probe));
        String touched = Files.readString(probe);
        WhaleHost.extractBundledWhale(root);
        assertEquals(touched, Files.readString(probe), "版本一致时不应重新解压覆盖");
    }
}

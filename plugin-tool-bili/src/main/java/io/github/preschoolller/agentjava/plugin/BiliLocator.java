package io.github.preschoolller.agentjava.plugin;

import io.github.preschoolller.agentjava.frontend.store.AppSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * bili 工具链（dsh-bili-whale 的 tools/ 目录，统一入口 bili.mjs）与 node 运行时的定位。
 *
 * 路径来源按优先级：
 *   1. 设置面板里「bili」或「bili_write」工具条目的交互输入（持久化在 settings，键 = 工具 name()）
 *   2. 环境变量 BILI_TOOLS_DIR（目录）或 BILI_MJS（bili.mjs 文件路径）
 *   3. 默认安装位置：~/.dsh/plugins/dsh-bili-whale/tools、~/.dsh/bili-whale/tools
 *
 * 每次执行前都会重新校验（不缓存 Path），持久化里存了失效路径也不至于静默走错地方——
 * 会回退到默认位置并在工具输出里说明。
 */
final class BiliLocator {

    /** dsh-bili-whale 插件的标准安装位置（dsh plugin add 落在 ~/.dsh/plugins/<包名>/） */
    private static final String DEFAULT_TOOLS_1 = Path.of(
            System.getProperty("user.home"), ".dsh", "plugins", "dsh-bili-whale", "tools").toString();
    private static final String DEFAULT_TOOLS_2 = Path.of(
            System.getProperty("user.home"), ".dsh", "bili-whale", "tools").toString();

    /** 设置面板持久化输入的两个键：bili（读工具）与 bili_write（写工具）共用一份工具链 */
    static final String PERSISTED_KEY_READ = "bili";
    static final String PERSISTED_KEY_WRITE = "bili_write";

    private BiliLocator() {
    }

    record Entry(Path biliMjs, String error) {
        boolean ok() {
            return biliMjs != null;
        }
    }

    /** 解析 bili.mjs 入口；失败时 error 里写清试过哪些位置、怎么配置 */
    static Entry resolveEntry() {
        List<String> tried = new ArrayList<>();
        for (String candidate : candidates()) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Path p = Path.of(candidate.trim().replaceAll("^\"|\"$", ""));
            if (Files.isDirectory(p)) {
                p = p.resolve("bili.mjs");
            }
            tried.add(p.toString());
            if (Files.isRegularFile(p)) {
                return new Entry(p.toAbsolutePath().normalize(), null);
            }
        }
        return new Entry(null, """
                未找到 bili.mjs（dsh-bili-whale 的 tools 目录）。尝试过的位置：
                %s
                配置方式（任选其一）：
                1. 应用设置 → 工具 → bili 条目，把 tools 目录（或 bili.mjs 完整路径）填进输入框点执行；
                2. 环境变量 BILI_TOOLS_DIR 指向 tools 目录（或 BILI_MJS 指向 bili.mjs 文件）。""".formatted(tried.isEmpty() ? "（无候选）" : String.join("\n", tried)));
    }

    private static List<String> candidates() {
        List<String> list = new ArrayList<>();
        AppSettings settings = AppSettings.get();
        for (String key : List.of(PERSISTED_KEY_READ, PERSISTED_KEY_WRITE)) {
            String v = settings.getPersistentPluginInput(key);
            if (v != null && !v.isBlank()) {
                list.add(v);
            }
        }
        String envDir = System.getenv("BILI_TOOLS_DIR");
        if (envDir != null && !envDir.isBlank()) {
            list.add(envDir);
        }
        String envMjs = System.getenv("BILI_MJS");
        if (envMjs != null && !envMjs.isBlank()) {
            list.add(envMjs);
        }
        list.add(DEFAULT_TOOLS_1);
        list.add(DEFAULT_TOOLS_2);
        // 插件 jar 里打包的 whale 副本解压后的 tools/（whale start 过一次就存在）——
        // 让新机器零配置可用：bili/bili_write 工具与 whale 宿主共用这一份自包含副本
        list.add(io.github.preschoolller.agentjava.backend.constant.Constants.APP_USER_DIR
                .resolve("whale").resolve("tools").toString());
        return list;
    }

    private static volatile String cachedNode;

    /**
     * node 运行时：BILI_NODE 环境变量 → PATH 里的 node → Windows 常见安装位置。
     * 找到后缓存；spawn 失败可调 invalidateNode() 重探。
     */
    static String resolveNode() {
        String cached = cachedNode;
        if (cached != null) {
            return cached;
        }
        String node = probeNode();
        if (node != null) {
            cachedNode = node;
        }
        return node;
    }

    static void invalidateNode() {
        cachedNode = null;
    }

    private static String probeNode() {
        String env = System.getenv("BILI_NODE");
        if (env != null && !env.isBlank() && Files.isRegularFile(Path.of(env))) {
            return env;
        }
        // PATH 里能不能直接起，起一次 -e "" 就知道了（顺带确认它能跑，不是坏了的 shim）
        if (canRun(List.of("node", "-e", ""))) {
            return "node";
        }
        for (String p : List.of(
                "C:\\Program Files\\nodejs\\node.exe",
                "C:\\Program Files (x86)\\nodejs\\node.exe")) {
            if (Files.isRegularFile(Path.of(p)) && canRun(List.of(p, "-e", ""))) {
                return p;
            }
        }
        return null;
    }

    private static boolean canRun(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * 设置面板交互：校验输入并给反馈。契约是返回非 "not implemented" 的反馈后
     * 前端会把输入按工具 name() 落盘——所以这里只管校验，坏路径在 resolveEntry 时
     * 会被再次校验并回退，不会静默生效。
     */
    static String uiInteractive(String input) {
        if (input == null || input.isBlank()) {
            Entry e = resolveEntry();
            if (e.ok()) {
                return "✓ 未填路径，但默认探测已命中：" + e.biliMjs();
            }
            return "✗ 未填路径且默认位置没找到 bili.mjs。请填 tools 目录（含 bili.mjs）或 bili.mjs 完整路径";
        }
        Path p = Path.of(input.trim().replaceAll("^\"|\"$", ""));
        Path mjs = Files.isDirectory(p) ? p.resolve("bili.mjs") : p;
        if (!Files.isRegularFile(mjs)) {
            return "✗ 没找到 " + mjs + "（输入会保存，但执行时会校验并回退默认位置）";
        }
        StringBuilder sb = new StringBuilder("✓ bili.mjs：").append(mjs.toAbsolutePath().normalize());
        String node = resolveNode();
        if (node == null) {
            sb.append("；✗ 未找到可用的 node 运行时（设置 BILI_NODE 或装 Node 18+）");
        } else {
            sb.append("；node：").append(node);
        }
        return sb.toString();
    }
}

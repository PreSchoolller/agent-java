package io.github.preschoolller.agentjava.plugin;

import io.github.preschoolller.agentjava.Main;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.plugin.api.AgentApiServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * dsh-shim 进程的生命周期管理（whale 工具的执行体）。
 *
 * start 做四件事，顺序有讲究：
 *   1. 定位 dsh-bili-whale 根目录（bili 工具链 tools/ 的上一级，共用一份定位配置）
 *   2. 拉起回环 API（AgentApiServer，Main.apiInject 承接注入）——shim 起来第一件事
 *      就是读 token 发请求，server 必须先在
 *   3. 把插件资源里的 dsh-shim.mjs 解压到 ~/.agentjava/shim/（每次覆盖：插件升级跟随）
 *   4. 起子进程 node shim，stdout/stderr 全量落 ~/.agentjava/logs/whale-shim.log
 *
 * stop：杀 shim 进程 → 收回环 API（生命周期耦合：监听跟着 shim 走）。
 * Windows 下 destroy 不走 node 的 SIGTERM 清理链，未完成的投递由原版
 * pendingRequest 落盘重试兜底，不会丢消息。
 */
final class WhaleHost {

    private static final Logger LOG = LoggerFactory.getLogger(WhaleHost.class);
    private static final Object LOCK = new Object();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private static volatile Process process;
    private static volatile Path logFile;

    private WhaleHost() {
    }

    // ---------------------------------------------------------------- start

    static String start() {
        synchronized (LOCK) {
            Process running = process;
            if (running != null && running.isAlive()) {
                return "已在运行（pid " + running.pid() + "）。" + pingLine();
            }
            // 1. 定位 dsh-bili-whale 根目录：bili.mjs 的上一级是 tools/，再上一级是插件根
            BiliLocator.Entry entry = BiliLocator.resolveEntry();
            if (!entry.ok()) {
                return entry.error();
            }
            Path whaleDir = entry.biliMjs().getParent().getParent();
            if (!Files.isRegularFile(whaleDir.resolve("lib").resolve("index.js"))) {
                return "工具链定位成功（" + entry.biliMjs() + "），但其上级目录不是完整的 dsh-bili-whale"
                        + "（缺 lib/index.js，宿主侧跑不起来）";
            }
            // 2. 回环 API（token 也在这一步懒生成，shim 启动时读的就是它）
            String apiMsg = AgentApiServer.start(new AgentApiServer.Delegate() {
                @Override
                public String ensureSession(String sessionId, String workspace) throws Exception {
                    return Main.apiEnsureSession(sessionId, workspace);
                }

                @Override
                public String inject(String sessionId, String text) throws Exception {
                    return Main.apiInject(sessionId, text);
                }
            });
            if (!AgentApiServer.running()) {
                return "回环 API 启动失败：" + apiMsg;
            }
            // 3. shim 解压
            Path shimPath;
            try {
                shimPath = extractShim();
            } catch (IOException e) {
                AgentApiServer.stop();
                return "shim 脚本解压失败：" + e.getMessage();
            }
            // 4. 起子进程（不经 shell，参数直传；输出全量进日志文件）
            String node = BiliLocator.resolveNode();
            if (node == null) {
                AgentApiServer.stop();
                return "未找到可用的 node 运行时（BILI_NODE 环境变量，或安装 Node 18+）";
            }
            Path log = Constants.APP_USER_DIR.resolve("logs").resolve("whale-shim.log");
            try {
                Files.createDirectories(log.getParent());
                ProcessBuilder pb = new ProcessBuilder(List.of(node, shimPath.toString()))
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
                pb.environment().put("AGENT_JAVA_API", "http://127.0.0.1:" + AgentApiServer.port());
                pb.environment().put("BILI_WHALE_DIR", whaleDir.toString());
                Process spawned = pb.start();
                process = spawned;
                logFile = log;
                LOG.info("dsh-shim started: pid={} whaleDir={} api={}",
                        spawned.pid(), whaleDir, pb.environment().get("AGENT_JAVA_API"));
                spawned.waitFor(1500, TimeUnit.MILLISECONDS); // 观察 1.5s：秒挂说明环境有问题，把日志尾部带回来
                if (!spawned.isAlive()) {
                    process = null;
                    AgentApiServer.stop();
                    return "shim 启动即退出（exit=" + spawned.exitValue() + "），日志尾部：\n" + tailOf(log, 20);
                }
            } catch (IOException e) {
                process = null;
                AgentApiServer.stop();
                return "shim 进程启动失败：" + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process = null;
                AgentApiServer.stop();
                return "启动等待被中断";
            }
            return "✓ 已启动（pid " + spawnedPid() + "）\n"
                    + "  · whale 目录：" + whaleDir + "\n"
                    + "  · 回环 API：" + pingLine() + "\n"
                    + "  · 日志：" + log + "（whale log 查看）\n"
                    + "生物钟/监听已按 ~/.dsh/bili-whale.config.json 生效：到点她会往会话 bili-whale 注入回合，"
                    + "走正常 agent 链路与权限门。保持应用开着（托盘常驻）即可。";
        }
    }

    private static long spawnedPid() {
        Process p = process;
        return p == null ? -1 : p.pid();
    }

    /** 插件资源里的 shim 解压到 ~/.agentjava/shim/（每次覆盖，升级跟随） */
    private static Path extractShim() throws IOException {
        Path target = Constants.APP_USER_DIR.resolve("shim").resolve("dsh-shim.mjs");
        Files.createDirectories(target.getParent());
        try (InputStream in = WhaleHost.class.getResourceAsStream("/dsh-shim.mjs")) {
            if (in == null) {
                throw new IOException("插件 jar 里没有 dsh-shim.mjs 资源（打包异常？）");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    // ---------------------------------------------------------------- stop

    static String stop() {
        synchronized (LOCK) {
            Process p = process;
            process = null;
            String shimResult;
            if (p == null || !p.isAlive()) {
                shimResult = "shim 本来就没在运行";
            } else {
                p.destroy();
                try {
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        p.waitFor(3, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                }
                shimResult = "shim 已停止（exit=" + p.exitValue() + "）";
            }
            AgentApiServer.stop();
            return shimResult + "；回环 API 已收。注：Windows 下不走 node 清理链，"
                    + "未完成投递由原版 pendingRequest 落盘重试兜底";
        }
    }

    // ---------------------------------------------------------------- status

    static String status() {
        Process p = process;
        String shimState = (p != null && p.isAlive())
                ? "shim：运行中（pid " + p.pid() + "）"
                : "shim：未运行";
        return shimState + "\n" + pingLine();
    }

    // ---------------------------------------------------------------- log

    static String log() {
        Path log = logFile;
        if (log == null) {
            log = Constants.APP_USER_DIR.resolve("logs").resolve("whale-shim.log");
        }
        if (!Files.isRegularFile(log)) {
            return "还没有日志文件（" + log + "）——先 start 一次";
        }
        return tailOf(log, 60);
    }

    // ---------------------------------------------------------------- helpers

    /** 回环 API 探活（带 token），一行可读结果 */
    private static String pingLine() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + AgentApiServer.port() + "/agent/api/ping"))
                    .timeout(Duration.ofSeconds(2))
                    .header("X-Agent-Token", AgentApiServer.token())
                    .GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return "回环 API：" + (response.statusCode() == 200 ? "✓ 正常" : "HTTP " + response.statusCode());
        } catch (IOException e) {
            return "回环 API：不可达（" + e.getMessage() + "）";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "回环 API：探测被中断";
        }
    }

    /** 日志尾部：最多读末尾 64KB 再取 N 行，防长跑日志膨胀拖垮工具输出 */
    private static String tailOf(Path log, int lines) {
        try {
            byte[] bytes = Files.readAllBytes(log);
            if (bytes.length > 64 * 1024) {
                byte[] cut = new byte[64 * 1024];
                System.arraycopy(bytes, bytes.length - cut.length, cut, 0, cut.length);
                bytes = cut;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] all = content.split("\r?\n");
            int from = Math.max(0, all.length - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < all.length; i++) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(all[i]);
            }
            return sb.isEmpty() ? "（日志为空）" : sb.toString();
        } catch (IOException | UncheckedIOException e) {
            return "日志读取失败：" + e.getMessage();
        }
    }
}

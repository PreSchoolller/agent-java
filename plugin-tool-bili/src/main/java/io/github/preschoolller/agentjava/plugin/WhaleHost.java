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
import java.util.Optional;
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
 * 存活判定（实战教训，勿回退）：**不信内存里的 Process 对象**——Windows 下实测
 * isAlive() 会假阴性（进程活着却报死），且应用重启后内存全空而 shim 作为独立进程
 * 活得很好，只看内存会叠着拉起多个实例（曾一晚叠出 3 个 shim，三份轮询三份生物钟）。
 * 所以 pid 落 ~/.agentjava/shim/shim.pid 作为跨重启的单一事实源，存活用
 * ProcessHandle.of(pid) 直查 OS。start 时发现 pid 文件里的进程还活着就接管不重拉。
 *
 * stop：按 pid 杀 shim（ProcessHandle.destroy，宽限 5s 后强杀）→ 收回环 API。
 * Windows 下 destroy 不走 node 的 SIGTERM 清理链，未完成的投递由原版
 * pendingRequest 落盘重试兜底，不会丢消息。
 */
final class WhaleHost {

    private static final Logger LOG = LoggerFactory.getLogger(WhaleHost.class);
    private static final Object LOCK = new Object();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final Path PID_FILE = Constants.APP_USER_DIR.resolve("shim").resolve("shim.pid");
    private static final Path LOG_FILE = Constants.APP_USER_DIR.resolve("logs").resolve("whale-shim.log");

    private WhaleHost() {
    }

    // ---------------------------------------------------------------- start

    static String start() {
        synchronized (LOCK) {
            // 0. 孤儿接管：pid 文件里的 shim 还活着（多半是上次应用重启留下的）就不重拉，
            //    只把回环 API 拉起来——旧 shim 的投递重试会自动落到新 server 上
            long existing = readPidFile();
            if (alive(existing)) {
                String apiMsg = ensureApi();
                if (!AgentApiServer.running()) {
                    return "shim 在跑（pid " + existing + "），但回环 API 启动失败：" + apiMsg
                            + "；旧 shim 的投递暂时无处可去，会按 pendingRequest 重试";
                }
                LOG.info("dsh-shim orphan adopted: pid={}", existing);
                return "✓ 已接管（pid " + existing + "，上次应用重启前拉起的实例，未重复拉起）\n"
                        + "  · 回环 API：" + pingLine() + "\n"
                        + "  · 日志：" + LOG_FILE + "（whale log 查看）\n"
                        + "生物钟/监听随该进程继续生效；保持应用开着（托盘常驻）即可。";
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
            String apiMsg = ensureApi();
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
            try {
                Files.createDirectories(LOG_FILE.getParent());
                ProcessBuilder pb = new ProcessBuilder(List.of(node, shimPath.toString()))
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.to(LOG_FILE.toFile()));
                pb.environment().put("AGENT_JAVA_API", "http://127.0.0.1:" + AgentApiServer.port());
                pb.environment().put("BILI_WHALE_DIR", whaleDir.toString());
                Process spawned = pb.start();
                long newPid = spawned.pid();
                LOG.info("dsh-shim started: pid={} whaleDir={} api={}", newPid, whaleDir,
                        pb.environment().get("AGENT_JAVA_API"));
                spawned.waitFor(1500, TimeUnit.MILLISECONDS); // 观察 1.5s：秒挂说明环境有问题，把日志尾部带回来
                if (!spawned.isAlive()) {
                    AgentApiServer.stop();
                    return "shim 启动即退出（exit=" + spawned.exitValue() + "），日志尾部：\n" + tailOf(LOG_FILE, 20);
                }
                writePidFile(newPid); // 起稳了才记账：pid 文件是跨重启的存活事实源
                return "✓ 已启动（pid " + newPid + "）\n"
                        + "  · whale 目录：" + whaleDir + "\n"
                        + "  · 回环 API：" + pingLine() + "\n"
                        + "  · 日志：" + LOG_FILE + "（whale log 查看）\n"
                        + "生物钟/监听已按 ~/.dsh/bili-whale.config.json 生效：到点她会往会话 bili-whale 注入回合，"
                        + "走正常 agent 链路与权限门。shim 是独立进程，应用重启也杀不死它——重启后不用重新 start，"
                        + "下次 start/status 会自动接管；想彻底停用才需要 stop（保持应用开着即可）。";
            } catch (IOException e) {
                AgentApiServer.stop();
                return "shim 进程启动失败：" + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AgentApiServer.stop();
                return "启动等待被中断";
            }
        }
    }

    /** 拉起回环 API（幂等），注入 Delegate 与用户输入同链路 */
    private static String ensureApi() {
        return AgentApiServer.start(new AgentApiServer.Delegate() {
            @Override
            public String ensureSession(String sessionId, String workspace) throws Exception {
                return Main.apiEnsureSession(sessionId, workspace);
            }

            @Override
            public String inject(String sessionId, String text) throws Exception {
                return Main.apiInject(sessionId, text);
            }
        });
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

    // ---------------------------------------------------------------- pid

    /** pid 文件读数（跨重启的单一事实源）：缺失/损坏/非数字一律 0 */
    private static long readPidFile() {
        try {
            if (!Files.isRegularFile(PID_FILE)) {
                return 0;
            }
            return Long.parseLong(Files.readString(PID_FILE, StandardCharsets.UTF_8).trim());
        } catch (IOException | UncheckedIOException | NumberFormatException e) {
            return 0;
        }
    }

    private static void writePidFile(long pid) {
        try {
            Files.createDirectories(PID_FILE.getParent());
            Files.writeString(PID_FILE, Long.toString(pid), StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("pid 文件写入失败（孤儿接管将失效）", e);
        }
    }

    private static void deletePidFile() {
        try {
            Files.deleteIfExists(PID_FILE);
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("pid 文件删除失败", e);
        }
    }

    /**
     * 存活判定直查 OS（ProcessHandle.of → isAlive），不依赖任何内存 Process 对象——
     * Windows 下实测后者会假阴性。pid<=0 一律不活。info().command() 双重确认：
     * 防的是 pid 被别的进程复用后误判活着。
     */
    static boolean alive(long pid) {
        if (pid <= 0) {
            return false;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        return handle.isPresent() && handle.get().isAlive()
                && handle.get().info().command().isPresent();
    }

    // ---------------------------------------------------------------- stop

    static String stop() {
        synchronized (LOCK) {
            long p = readPidFile();
            String shimResult;
            if (!alive(p)) {
                shimResult = "shim 本来就没在运行";
            } else {
                Optional<ProcessHandle> handle = ProcessHandle.of(p);
                handle.ifPresent(ProcessHandle::destroy);
                try {
                    for (int i = 0; i < 10 && alive(p); i++) {
                        TimeUnit.MILLISECONDS.sleep(500);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (alive(p)) {
                    handle.ifPresent(ProcessHandle::destroyForcibly);
                }
                shimResult = "shim 已停止（pid " + p + "）";
            }
            deletePidFile();
            AgentApiServer.stop();
            return shimResult + "；回环 API 已收。注：Windows 下不走 node 清理链，"
                    + "未完成投递由原版 pendingRequest 落盘重试兜底";
        }
    }

    // ---------------------------------------------------------------- status

    static String status() {
        long p = readPidFile();
        String shimState = alive(p)
                ? "shim：运行中（pid " + p + "）"
                : "shim：未运行" + (p > 0 ? "（pid " + p + " 已退出，重新 start 即可）" : "");
        return shimState + "\n" + pingLine() + "\n"
                + "（回环 API 由 agent-java 自己提供，✓ 只代表宿主在听，不代表 shim 活着）";
    }

    // ---------------------------------------------------------------- log

    static String log() {
        if (!Files.isRegularFile(LOG_FILE)) {
            return "还没有日志文件（" + LOG_FILE + "）——先 start 一次";
        }
        return tailOf(LOG_FILE, 60);
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

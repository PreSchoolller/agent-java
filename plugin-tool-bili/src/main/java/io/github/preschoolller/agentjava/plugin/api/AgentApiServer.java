package io.github.preschoolller.agentjava.plugin.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本机回环 API：给 dsh-shim（伪 DSH 宿主的 Node 适配层）提供"往会话注入一回合"的入口。
 *
 * 背景见插件资源 dsh-shim.mjs：dsh-bili-whale 的宿主侧（lib/）只通过 sessionController 的
 * create/prompt 参与自治链路，webServer/connection 仅服务设置页。shim 把 prompt 转成
 * 这里的 HTTP 调用，Java 侧走与用户输入完全相同的链路（落库→建卡→agent loop，
 * 由 Main.apiEnsureSession / Main.apiInject 承接）。
 *
 * 由 WhaleHost 在 whale 工具 start 时拉起，stop 时收掉——生命周期跟着"她"走。
 *
 * 安全模型（个人桌面应用，够用就好）：
 *   · 只绑 127.0.0.1，外部机器不可达
 *   · 共享密钥鉴权：token 落在 ~/.agentjava/api-token（首次启动生成 32 位随机），
 *     shim 同机读文件，不经过网络传输；防的是"任意网页向回环发请求"这类 CSRF 面
 *   · 请求体大小上限 1MB，session/text 均为必填非空
 *
 * 端口：默认 18765，环境变量 AGENT_JAVA_API_PORT 覆盖。端口被占（多半是开了第二个实例）
 * 时 start 返回失败说明，不影响应用其余功能。
 */
public final class AgentApiServer {

    /** 宿主接线口：WhaleHost 实现（EDT 上 find-or-create 会话 + 走用户输入同链路） */
    public interface Delegate {
        /** 按 id 查找或创建会话（不注入内容），返回会话 id；workspace 可空 */
        String ensureSession(String sessionId, String workspace) throws Exception;

        /** 注入一条用户回合（落库+出卡+启动链），返回会话 id */
        String inject(String sessionId, String text) throws Exception;
    }

    private static final Logger LOG = LoggerFactory.getLogger(AgentApiServer.class);
    private static final int MAX_BODY_BYTES = 1 << 20;
    private static final AtomicReference<HttpServer> INSTANCE = new AtomicReference<>();

    private AgentApiServer() {
    }

    /**
     * 启动（幂等）。返回人类可读结果：已在运行 / 已启动（含端口） / 失败原因。
     * WhaleHost 把失败当错误返回给模型，不静默。
     */
    public static synchronized String start(Delegate delegate) {
        if (INSTANCE.get() != null) {
            return "已运行（端口 " + INSTANCE.get().getAddress().getPort() + "）";
        }
        try {
            HttpServer server = startOn(port(), delegate);
            INSTANCE.set(server);
            LOG.info("agent api started: http://127.0.0.1:{} （token 文件 {}）",
                    server.getAddress().getPort(), tokenPath());
            return "已启动（端口 " + server.getAddress().getPort() + "）";
        } catch (BindException e) {
            String msg = "端口 " + port() + " 被占用（多半是第二个实例在跑，或别的程序占了）";
            LOG.warn("agent api 启动失败：{}", msg);
            return msg;
        } catch (IOException e) {
            LOG.error("agent api 启动失败", e);
            return describe(e);
        }
    }

    /** 是否已在监听 */
    public static boolean running() {
        return INSTANCE.get() != null;
    }

    /** 建服务器并启动（端口 0 = 系统分配，测试用）；返回的 server 由调用方负责 stop */
    static HttpServer startOn(int port, Delegate delegate) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        String token = token();
        server.createContext("/agent/api", ex -> {
            try {
                handle(ex, token, delegate);
            } catch (IllegalArgumentException e) {
                json(ex, 400, error(describe(e)));
            } catch (Exception e) {
                LOG.error("agent api 处理失败", e);
                json(ex, 500, error(describe(e)));
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "agent-api");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();
        return server;
    }

    public static synchronized void stop() {
        HttpServer server = INSTANCE.getAndSet(null);
        if (server != null) {
            server.stop(0);
            LOG.info("agent api stopped");
        }
    }

    public static int port() {
        String env = System.getenv("AGENT_JAVA_API_PORT");
        if (env != null && env.matches("\\d+")) {
            return Integer.parseInt(env);
        }
        return 18765;
    }

    public static Path tokenPath() {
        return Constants.APP_USER_DIR.resolve("api-token");
    }

    /** token 懒生成：首次调用创建 32 位十六进制，落盘后不再变（shim 每次启动读同一份） */
    public static String token() throws IOException {
        Path path = tokenPath();
        if (Files.isRegularFile(path)) {
            String token = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        Files.createDirectories(path.getParent());
        Files.writeString(path, token, StandardCharsets.UTF_8);
        return token;
    }

    private static void handle(HttpExchange ex, String token, Delegate delegate) throws Exception {
        if (!"127.0.0.1".equals(ex.getLocalAddress().getHostName())
                && !"127.0.0.1".equals(ex.getLocalAddress().getAddress().getHostAddress())) {
            json(ex, 403, error("非回环访问"));
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (!"/agent/api/ping".equals(path) && !"/agent/api/session".equals(path) && !"/agent/api/prompt".equals(path)) {
            json(ex, 404, error("unknown path"));
            return;
        }
        String given = ex.getRequestHeaders().getFirst("X-Agent-Token");
        if (given == null || !constantTimeEquals(given, token)) {
            json(ex, 401, error("token 不匹配（shim 读 " + tokenPath() + "）"));
            return;
        }
        switch (path) {
            case "/agent/api/ping" -> json(ex, 200, ok().put("pong", true));
            case "/agent/api/session" -> {
                if (!"POST".equals(ex.getRequestMethod())) {
                    json(ex, 405, error("POST only"));
                    return;
                }
                JsonNode body = body(ex);
                String sessionId = required(body, "sessionId");
                String conversationId = delegate.ensureSession(sessionId, body.path("workspace").asText(null));
                json(ex, 200, ok().put("conversationId", conversationId));
            }
            case "/agent/api/prompt" -> {
                if (!"POST".equals(ex.getRequestMethod())) {
                    json(ex, 405, error("POST only"));
                    return;
                }
                JsonNode body = body(ex);
                String sessionId = required(body, "sessionId");
                String text = required(body, "text");
                String conversationId = delegate.inject(sessionId, text);
                json(ex, 200, ok().put("conversationId", conversationId));
            }
            default -> json(ex, 404, error("unknown path"));
        }
    }

    private static JsonNode body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("请求体超过 1MB");
            }
            JsonNode node;
            try {
                node = Constants.getObjectMapper().readTree(bytes);
            } catch (IOException e) {
                throw new IllegalArgumentException("请求体不是合法 JSON: " + describe(e));
            }
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("请求体必须是 JSON 对象");
            }
            return node;
        }
    }

    private static String required(JsonNode body, String field) {
        String value = body.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺必填字段: " + field);
        }
        return value;
    }

    private static ObjectNode ok() {
        return Constants.getObjectMapper().createObjectNode().put("ok", true);
    }

    private static ObjectNode error(String message) {
        return Constants.getObjectMapper().createObjectNode().put("ok", false).put("error", message);
    }

    private static void json(HttpExchange ex, int status, ObjectNode node) throws IOException {
        byte[] bytes = node.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null ? t.toString() : message;
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int diff = x.length ^ y.length;
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }
}

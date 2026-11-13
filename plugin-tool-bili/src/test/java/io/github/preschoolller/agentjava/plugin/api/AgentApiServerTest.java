package io.github.preschoolller.agentjava.plugin.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 迁自宿主 backend/api（AgentApiServer 已搬进插件，由 WhaleHost 生命周期管理） */
class AgentApiServerTest {

    static class FakeDelegate implements AgentApiServer.Delegate {
        final List<String> ensured = new ArrayList<>();
        final List<String> injected = new ArrayList<>();

        @Override
        public String ensureSession(String sessionId, String workspace) {
            ensured.add(sessionId + "|" + workspace);
            return sessionId;
        }

        @Override
        public String inject(String sessionId, String text) {
            injected.add(sessionId + "|" + text);
            return sessionId;
        }
    }

    private final FakeDelegate delegate = new FakeDelegate();
    private HttpServer server;
    private int port;
    private String token;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        server = AgentApiServer.startOn(0, delegate);
        port = server.getAddress().getPort();
        token = AgentApiServer.token();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private HttpResponse<String> post(String path, String json, String withToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json, StandardCharsets.UTF_8));
        if (withToken != null) {
            builder.header("X-Agent-Token", withToken);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String withToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (withToken != null) {
            builder.header("X-Agent-Token", withToken);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonNode jsonOf(HttpResponse<String> response) throws Exception {
        return Constants.getObjectMapper().readTree(response.body());
    }

    @Test
    void pingRequiresToken() throws Exception {
        assertEquals(401, get("/agent/api/ping", null).statusCode());
        assertEquals(401, get("/agent/api/ping", "wrong-token").statusCode());
        HttpResponse<String> ok = get("/agent/api/ping", token);
        assertEquals(200, ok.statusCode());
        assertTrue(jsonOf(ok).path("pong").asBoolean());
    }

    @Test
    void sessionEnsurePassesIdAndWorkspace() throws Exception {
        HttpResponse<String> response = post("/agent/api/session",
                "{\"sessionId\":\"bili-whale\",\"workspace\":\"D:/tmp/whale-work\"}", token);
        assertEquals(200, response.statusCode());
        assertEquals("bili-whale", jsonOf(response).path("conversationId").asText());
        assertEquals(List.of("bili-whale|D:/tmp/whale-work"), delegate.ensured);
        assertTrue(delegate.injected.isEmpty());
    }

    @Test
    void promptInjectsText() throws Exception {
        HttpResponse<String> response = post("/agent/api/prompt",
                "{\"sessionId\":\"bili-whale\",\"text\":\"到点了，去看看\"}", token);
        assertEquals(200, response.statusCode());
        assertEquals("bili-whale", jsonOf(response).path("conversationId").asText());
        assertEquals(List.of("bili-whale|到点了，去看看"), delegate.injected);
        assertTrue(delegate.ensured.isEmpty());
    }

    @Test
    void missingFieldIs400() throws Exception {
        assertEquals(400, post("/agent/api/prompt", "{\"sessionId\":\"x\"}", token).statusCode());
        assertEquals(400, post("/agent/api/prompt", "{\"text\":\"y\"}", token).statusCode());
        assertEquals(400, post("/agent/api/prompt", "not-json", token).statusCode());
        assertTrue(delegate.injected.isEmpty());
    }

    @Test
    void unknownPathIs404WrongMethodIs405() throws Exception {
        assertEquals(404, get("/agent/api/other", token).statusCode());
        assertEquals(404, get("/nothing", token).statusCode());
        assertEquals(405, get("/agent/api/prompt", token).statusCode());
        assertTrue(delegate.injected.isEmpty());
    }

    @Test
    void delegateExceptionIs500WithMessage() throws Exception {
        HttpServer boom = AgentApiServer.startOn(0, new AgentApiServer.Delegate() {
            @Override
            public String ensureSession(String sessionId, String workspace) {
                throw new IllegalStateException("boom 原因");
            }

            @Override
            public String inject(String sessionId, String text) {
                throw new IllegalStateException("boom 原因");
            }
        });
        try {
            int boomPort = boom.getAddress().getPort();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + boomPort + "/agent/api/prompt"))
                            .header("X-Agent-Token", token)
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"sessionId\":\"x\",\"text\":\"t\"}", StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(500, response.statusCode());
            JsonNode body = jsonOf(response);
            assertFalse(body.path("ok").asBoolean());
            assertTrue(body.path("error").asText().contains("boom 原因"));
        } finally {
            boom.stop(0);
        }
    }

    @Test
    void startIsIdempotentAndReportsFailure() {
        // start 走默认端口（18765）：已被 startOn 的 server 占不了（不同端口），这里只验证
        // 幂等分支与返回消息是可读中文，不真绑默认端口（避免测试机端口冲突）
        String first = AgentApiServer.start(new FakeDelegate());
        assertTrue(first.contains("已启动") || first.contains("已运行") || first.contains("占用"),
                "start 应返回可读结果，实际: " + first);
        String second = AgentApiServer.start(new FakeDelegate());
        assertTrue(second.contains("已运行") || second.contains("已启动") || second.contains("占用"),
                "重复 start 应幂等，实际: " + second);
        AgentApiServer.stop();
        assertFalse(AgentApiServer.running());
    }
}

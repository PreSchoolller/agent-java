package io.github.preschoolller.agentjava.frontend;

import io.github.preschoolller.agentjava.core.records.McpServerView;
import io.github.preschoolller.agentjava.core.records.ModelView;
import io.github.preschoolller.agentjava.core.records.ProfileView;
import io.github.preschoolller.agentjava.core.repository.McpServer;
import io.github.preschoolller.agentjava.core.repository.Profile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置投影 ↔ 编辑用数据类的往返：设置面板把 {@link ProfileView} 摊成可编辑副本，
 * 保存时再收回去，字段不能丢；密钥类字段按「空白 = 不更改」处理。
 */
class ViewAdapterTest {

    @Test
    void profileRoundTripKeepsEveryField() {
        Profile source = new Profile();
        source.setBackendUrl("http://127.0.0.1:11434/v1");
        source.setKey("sk-secret");
        source.setApiType(Profile.API_MESSAGES);
        ModelView model = new ModelView("claude", "200000", "8192", "HIGH", true, true);
        source.setModelMetas(List.of(ViewAdapter.modelMeta(model)));
        source.setModel("claude");
        source.setTemperature(0.3);
        source.setMaxTokens(4096);
        source.setSystemPrompt("你是助手");

        ProfileView view = ViewAdapter.profileView("主方案", source);
        Profile back = ViewAdapter.profile(view);

        assertEquals("主方案", view.name());
        assertEquals("sk-secret", view.key(), "非空密钥原样提交");
        assertEquals("http://127.0.0.1:11434/v1", back.getBackendUrl());
        assertEquals(Profile.API_MESSAGES, back.getApiType());
        assertEquals("claude", back.getModel());
        assertEquals(0.3, back.getTemperature(), 0.0001);
        assertEquals(4096, back.getMaxTokens());
        assertEquals("你是助手", back.getSystemPrompt());
        assertEquals(1, back.getModelMetas().size());
        assertEquals("200000", back.getModelMetas().get(0).getCtxWindow());
        assertEquals("HIGH", back.getModelMetas().get(0).getReasoning());
        assertTrue(back.getModelMetas().get(0).isImage());
    }

    @Test
    void blankKeyMeansUnchanged() {
        Profile source = new Profile();
        source.setKey("   "); // 投影不回传密钥时面板留空，保存不能把它当成「清空」
        ProfileView view = ViewAdapter.profileView("方案", source);
        assertNull(view.key());

        Profile empty = new Profile();
        empty.setKey("");
        assertNull(ViewAdapter.profileView("方案", empty).key());
    }

    @Test
    void nullKeyFromProjectionShowsAsEmptyField() {
        ProfileView view = new ProfileView("方案", Profile.API_RESPONSE, "http://x", null,
                "m", List.of(new ModelView("m", "", "", "", true, false)), 0.7, 2048, "");
        assertEquals("", ViewAdapter.profile(view).getKey());
    }

    @Test
    void mcpRoundTripKeepsEveryField() {
        McpServer server = new McpServer();
        server.setName("本地");
        server.setTransport(McpServer.TRANSPORT_STDIO);
        server.setProtocol(McpServer.PROTOCOL_MODERN);
        server.setEnabled(false);
        server.setCommand("npx");
        server.setArgs(List.of("-y", "@modelcontextprotocol/server"));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TOKEN", "abc");
        server.setEnv(env);
        server.setUrl("http://127.0.0.1:3000/mcp");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer x");
        server.setHeaders(headers);

        McpServerView view = ViewAdapter.mcpServerView(server);
        McpServer back = ViewAdapter.mcpServer(view);

        assertEquals("本地", back.getName());
        assertEquals(McpServer.TRANSPORT_STDIO, back.getTransport());
        assertEquals(McpServer.PROTOCOL_MODERN, back.getProtocol());
        assertEquals(false, back.isEnabled());
        assertEquals("npx", back.getCommand());
        assertEquals(List.of("-y", "@modelcontextprotocol/server"), back.getArgs());
        assertEquals("abc", back.getEnv().get("TOKEN"));
        assertEquals("http://127.0.0.1:3000/mcp", back.getUrl());
        assertEquals("Bearer x", back.getHeaders().get("Authorization"));
    }

    @Test
    void findModelScansAllProfiles() {
        ProfileView first = new ProfileView("A", Profile.API_RESPONSE, "", null, "x",
                List.of(new ModelView("x", "", "", "", true, false)), 0.7, 2048, "");
        ProfileView second = new ProfileView("B", Profile.API_MESSAGES, "", null, "y",
                List.of(new ModelView("y", "", "8192", "LOW", true, false)), 0.7, 2048, "");
        assertEquals("LOW", ViewAdapter.findModel(List.of(first, second), "y").reasoning());
        assertNull(ViewAdapter.findModel(List.of(first, second), "不存在"));
    }
}

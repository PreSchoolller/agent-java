package io.github.preschoolller.agentjava.api;

/**
 * 宿主会话能力面：外部系统（如 dsh-shim）按自己的 sessionId 驱动 agent-java 的会话。
 * 由宿主实现，启动装配阶段经 {@link AgentJavaSessions#install} 注入。
 *
 * <p>sessionId 的所有权在调用方：宿主按 id 查找或创建会话（幂等 upsert），
 * 不自行生成新 id。这样调用方重启后拿同一个 id 仍能接上原会话历史，
 * 不需要在两侧之间再维护一份 id 映射表。
 */
public interface AgentJavaSessionProvider {

    /**
     * 按 id 查找或创建会话（不注入内容），返回宿主承认的会话 id。
     *
     * <p>幂等：id 已存在时直接复用且不改动其 workspace；只有新建时 {@code workspace} 才生效。
     *
     * @param sessionId 调用方的会话标识，非空
     * @param workspace 会话工作区，可为 null（回落默认工作区，仅新建时生效）
     * @return 会话 id（当前实现恒等于入参 sessionId）
     */
    String ensureSession(String sessionId, String workspace) throws Exception;

    /**
     * 注入一条用户回合：落库 + 出卡 + 启动 agent 链，与用户在 UI 里发送同链路。
     *
     * <p>注意：会话不存在时按该 id 新建，但此时 workspace 为 null。依赖工作区的场景
     * 应先调 {@link #ensureSession} 再注入。
     *
     * @param sessionId 会话 id
     * @param text      用户消息原文，非空
     * @return 会话 id（当前实现恒等于入参 sessionId）
     */
    String inject(String sessionId, String text) throws Exception;
}

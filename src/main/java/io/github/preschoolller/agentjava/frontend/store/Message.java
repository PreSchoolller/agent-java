package io.github.preschoolller.agentjava.frontend.store;

/** 一条消息。role 取 Conversation.ROLE_USER 或 Conversation.ROLE_AI。 */
public record Message(String role, String text, long time) {
}

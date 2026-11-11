package com.echomind.conversation;

/** 一轮对话（一问一答）落库所需的信息。 */
public record ChatTurn(
        String conversationId,
        String userId,
        String requestId,
        String userMessage,
        String assistantMessage,
        String intent,
        String agentType,
        boolean escalated,
        long latencyMs
) {
}

package com.echomind.ticket;

/** 转人工建单所需的信息。 */
public record Escalation(
        String conversationId,
        String userId,
        String priority,
        String intent,
        String reason,
        String summary,
        String sourceRequestId
) {
}

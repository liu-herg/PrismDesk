package com.echomind.notify;

import com.echomind.store.TicketEntity;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 工单变化通知。坐席收到全部；客户只收到自己工单的。
 *
 * @param type     ticket.created / ticket.updated
 * @param action   create / claim / release / resolve / close / reopen
 * @param operator 谁做的操作（系统自动建单时为 system）
 * @param userId   工单所属客户，用来决定推给哪个客户
 */
public record TicketNotice(String type, long ticketId, String action, String status, String operator,
                           String operatorName, String userId, String conversationId, String assignee,
                           String priority, String reason, LocalDateTime at) {

    public static TicketNotice created(TicketEntity t) {
        return of("ticket.created", t, "create", "system", "系统");
    }

    public static TicketNotice updated(TicketEntity t, String action, String operator, String operatorName) {
        return of("ticket.updated", t, action, operator, operatorName);
    }

    private static TicketNotice of(String type, TicketEntity t, String action, String operator, String operatorName) {
        return new TicketNotice(type, t.getId(), action.toLowerCase(Locale.ROOT),
                t.getStatus() == null ? null : t.getStatus().toLowerCase(Locale.ROOT), operator, operatorName,
                t.getUserId(), t.getConversationId(), t.getAssignee(), t.getPriority(), t.getReason(),
                LocalDateTime.now());
    }
}

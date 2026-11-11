package com.echomind.ticket;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 工单状态机：每个操作只允许在特定状态下执行，并转移到确定的目标状态。
 *
 * <pre>
 *            claim               resolve              close
 *  PENDING ────────▶ IN_PROGRESS ────────▶ RESOLVED ────────▶ CLOSED
 *     ▲  │  ◀────────     │                   │
 *     │  │   release      │      ◀────────────┘
 *     │  │                │        reopen
 *     │  └──── close ─────┼──────────────────────────────────▶ CLOSED
 * </pre>
 */
public enum TicketAction {
    /** 坐席认领待分配的工单。 */
    CLAIM(EnumSet.of(TicketStatus.PENDING), TicketStatus.IN_PROGRESS, false),
    /** 坐席退回自己手上的工单，重新进入待分配。 */
    RELEASE(EnumSet.of(TicketStatus.IN_PROGRESS), TicketStatus.PENDING, true),
    /** 坐席标记自己手上的工单已解决。 */
    RESOLVE(EnumSet.of(TicketStatus.IN_PROGRESS), TicketStatus.RESOLVED, true),
    /** 关闭：已解决的工单确认关闭，或者无效的待分配工单直接关闭。 */
    CLOSE(EnumSet.of(TicketStatus.PENDING, TicketStatus.RESOLVED), TicketStatus.CLOSED, false),
    /** 已解决但用户反馈没解决，重新打开，交还给原坐席。 */
    REOPEN(EnumSet.of(TicketStatus.RESOLVED), TicketStatus.IN_PROGRESS, false);

    private final Set<TicketStatus> allowedFrom;
    private final TicketStatus target;
    private final boolean assigneeOnly;

    TicketAction(Set<TicketStatus> allowedFrom, TicketStatus target, boolean assigneeOnly) {
        this.allowedFrom = allowedFrom;
        this.target = target;
        this.assigneeOnly = assigneeOnly;
    }

    public boolean canApplyTo(TicketStatus current) {
        return allowedFrom.contains(current);
    }

    public TicketStatus target() {
        return target;
    }

    /** 是否只有当前处理人才能执行（比如不能替别人标记"已解决"）。 */
    public boolean assigneeOnly() {
        return assigneeOnly;
    }

    public static TicketAction parse(String value) {
        try {
            return TicketAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("未知的工单操作: " + value);
        }
    }
}

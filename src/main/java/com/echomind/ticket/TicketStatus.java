package com.echomind.ticket;

public enum TicketStatus {
    /** 待分配：刚创建，还没有坐席接手。 */
    PENDING,
    /** 处理中：已被某个坐席认领。 */
    IN_PROGRESS,
    /** 已解决：坐席处理完毕，可以关闭，也可以重新打开。 */
    RESOLVED,
    /** 已关闭：终态，不能再变化。 */
    CLOSED;

    /** 未结状态。同一会话同时只能有一张未结工单（由数据库唯一索引保证）。 */
    public boolean isOpen() {
        return this == PENDING || this == IN_PROGRESS;
    }
}

package com.echomind.ticket;

import com.echomind.store.TicketEntity;
import com.echomind.store.TicketEventEntity;
import com.echomind.store.TicketEventMapper;
import com.echomind.store.TicketMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TicketService {

    static final int MAX_PAGE_SIZE = 100;
    static final String SYSTEM = "system";

    private final TicketMapper ticketMapper;
    private final TicketEventMapper eventMapper;

    public TicketService(TicketMapper ticketMapper, TicketEventMapper eventMapper) {
        this.ticketMapper = ticketMapper;
        this.eventMapper = eventMapper;
    }

    /**
     * 转人工时建单，幂等：同一会话已有未结工单时不再新建，直接返回已有的那张。
     *
     * <p>不用"先查有没有、没有再插入"：两个并发请求可能都查到没有，然后各插一张。
     * 这里直接插入，靠唯一索引 uk_open_conversation 兜底，冲突时再查出已有的工单。
     */
    @Transactional
    public EscalationResult openForEscalation(Escalation escalation) {
        LocalDateTime now = LocalDateTime.now();
        TicketEntity ticket = new TicketEntity();
        ticket.setConversationId(escalation.conversationId());
        ticket.setUserId(escalation.userId());
        ticket.setStatus(TicketStatus.PENDING.name());
        ticket.setPriority(escalation.priority());
        ticket.setIntent(escalation.intent());
        ticket.setReason(truncate(escalation.reason(), 255));
        ticket.setSummary(truncate(escalation.summary(), 4000));
        ticket.setSourceRequestId(escalation.sourceRequestId());
        ticket.setVersion(0);
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        try {
            ticketMapper.insert(ticket);
        } catch (DuplicateKeyException duplicate) {
            TicketEntity existing = ticketMapper.selectOpenByConversation(escalation.conversationId());
            if (existing == null) {
                // 极少见：冲突的那张工单刚好在这一瞬间被关闭了
                throw new ResponseStatusException(HttpStatus.CONFLICT, "建单冲突，请重试");
            }
            return new EscalationResult(existing, false);
        }
        recordEvent(ticket.getId(), "CREATE", null, TicketStatus.PENDING, SYSTEM, ticket.getReason(), now);
        return new EscalationResult(ticket, true);
    }

    /**
     * 执行一次状态转移。三道检查依次是：状态机是否允许、操作人是否有权限、版本号是否还是读到的那个。
     *
     * @param expectedVersion 调用方看到的版本号；传了就要求一致（防止基于过期页面做操作），不传则以本次读到的为准
     */
    @Transactional
    public TicketEntity transition(long ticketId, TicketAction action, String operator, Integer expectedVersion, String note) {
        if (operator == null || operator.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operator 不能为空");
        }
        TicketEntity current = ticketMapper.selectById(ticketId);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }
        if (expectedVersion != null && !expectedVersion.equals(current.getVersion())) {
            throw new TicketConflictException("工单已被修改，请刷新后重试（当前版本 " + current.getVersion() + "）");
        }
        TicketStatus from = TicketStatus.valueOf(current.getStatus());
        if (!action.canApplyTo(from)) {
            throw new TicketConflictException("工单当前状态为 " + from + "，不能执行 " + action);
        }
        if (action.assigneeOnly() && !operator.equals(current.getAssignee())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有当前处理人才能执行 " + action);
        }

        LocalDateTime now = LocalDateTime.now();
        TicketEntity next = copy(current);
        next.setStatus(action.target().name());
        next.setUpdatedAt(now);
        switch (action) {
            case CLAIM -> {
                next.setAssignee(operator);
                next.setAssignedAt(now);
            }
            case RELEASE -> {
                next.setAssignee(null);
                next.setAssignedAt(null);
            }
            case RESOLVE -> next.setResolvedAt(now);
            case CLOSE -> next.setClosedAt(now);
            case REOPEN -> next.setResolvedAt(null);
        }

        int updated;
        try {
            updated = ticketMapper.updateWithVersion(next, current.getVersion());
        } catch (DuplicateKeyException duplicate) {
            // 重新打开时，这个会话可能已经有了一张新的未结工单
            throw new TicketConflictException("该会话已有未结工单，不能重新打开这张");
        }
        if (updated == 0) {
            // 读和写之间被别人抢先修改了（比如两个坐席同时认领）
            throw new TicketConflictException("工单已被他人处理，请刷新后重试");
        }
        next.setVersion(current.getVersion() + 1);
        recordEvent(ticketId, action.name(), from, action.target(), operator, note, now);
        return next;
    }

    public TicketEntity get(long ticketId) {
        TicketEntity ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }
        return ticket;
    }

    public List<TicketEventEntity> events(long ticketId) {
        return eventMapper.selectByTicket(ticketId);
    }

    public List<TicketEntity> list(TicketStatus status, String assignee, Long beforeId, int size) {
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        return ticketMapper.page(status == null ? null : status.name(), blankToNull(assignee), beforeId, pageSize);
    }

    private void recordEvent(Long ticketId, String action, TicketStatus from, TicketStatus to,
                             String operator, String note, LocalDateTime at) {
        TicketEventEntity event = new TicketEventEntity();
        event.setTicketId(ticketId);
        event.setAction(action);
        event.setFromStatus(from == null ? null : from.name());
        event.setToStatus(to.name());
        event.setOperator(operator);
        event.setNote(truncate(note, 500));
        event.setCreatedAt(at);
        eventMapper.insert(event);
    }

    private static TicketEntity copy(TicketEntity source) {
        TicketEntity t = new TicketEntity();
        t.setId(source.getId());
        t.setConversationId(source.getConversationId());
        t.setUserId(source.getUserId());
        t.setStatus(source.getStatus());
        t.setPriority(source.getPriority());
        t.setIntent(source.getIntent());
        t.setReason(source.getReason());
        t.setSummary(source.getSummary());
        t.setSourceRequestId(source.getSourceRequestId());
        t.setAssignee(source.getAssignee());
        t.setVersion(source.getVersion());
        t.setCreatedAt(source.getCreatedAt());
        t.setUpdatedAt(source.getUpdatedAt());
        t.setAssignedAt(source.getAssignedAt());
        t.setResolvedAt(source.getResolvedAt());
        t.setClosedAt(source.getClosedAt());
        return t;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** created=false 表示这个会话已经有未结工单，本次没有新建。 */
    public record EscalationResult(TicketEntity ticket, boolean created) {
    }
}

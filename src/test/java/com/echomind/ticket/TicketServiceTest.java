package com.echomind.ticket;

import com.echomind.store.TicketEntity;
import com.echomind.store.TicketEventEntity;
import com.echomind.store.TicketEventMapper;
import com.echomind.store.TicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketServiceTest {

    private TicketMapper ticketMapper;
    private TicketEventMapper eventMapper;
    private TicketService service;

    @BeforeEach
    void setUp() {
        ticketMapper = mock(TicketMapper.class);
        eventMapper = mock(TicketEventMapper.class);
        service = new TicketService(ticketMapper, eventMapper);
    }

    @Test
    void claimSetsAssigneeAndBumpsVersion() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, TicketStatus.PENDING, null, 3));
        when(ticketMapper.updateWithVersion(any(), eq(3))).thenReturn(1);

        TicketEntity result = service.transition(1L, TicketAction.CLAIM, "agent-a", null, null);

        assertThat(result.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(result.getAssignee()).isEqualTo("agent-a");
        assertThat(result.getAssignedAt()).isNotNull();
        assertThat(result.getVersion()).isEqualTo(4);
        ArgumentCaptor<TicketEventEntity> event = ArgumentCaptor.forClass(TicketEventEntity.class);
        verify(eventMapper).insert(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo("CLAIM");
        assertThat(event.getValue().getFromStatus()).isEqualTo("PENDING");
        assertThat(event.getValue().getToStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void concurrentModificationBetweenReadAndWriteIsRejected() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, TicketStatus.PENDING, null, 0));
        // 模拟：读到 version=0 之后，别人已经抢先改过了，条件更新影响 0 行
        when(ticketMapper.updateWithVersion(any(), eq(0))).thenReturn(0);

        assertThatThrownBy(() -> service.transition(1L, TicketAction.CLAIM, "agent-b", null, null))
                .isInstanceOf(TicketConflictException.class);
        verify(eventMapper, never()).insert(any(TicketEventEntity.class));
    }

    @Test
    void staleVersionFromClientIsRejectedBeforeWriting() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, TicketStatus.IN_PROGRESS, "agent-a", 5));

        assertThatThrownBy(() -> service.transition(1L, TicketAction.RESOLVE, "agent-a", 4, "done"))
                .isInstanceOf(TicketConflictException.class);
        verify(ticketMapper, never()).updateWithVersion(any(), anyInt());
    }

    @Test
    void illegalTransitionIsRejected() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, TicketStatus.CLOSED, "agent-a", 2));

        assertThatThrownBy(() -> service.transition(1L, TicketAction.REOPEN, "agent-a", null, null))
                .isInstanceOf(TicketConflictException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    void onlyAssigneeCanResolve() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, TicketStatus.IN_PROGRESS, "agent-a", 1));

        assertThatThrownBy(() -> service.transition(1L, TicketAction.RESOLVE, "agent-b", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void releaseClearsAssignee() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, TicketStatus.IN_PROGRESS, "agent-a", 1));
        when(ticketMapper.updateWithVersion(any(), eq(1))).thenReturn(1);

        TicketEntity result = service.transition(1L, TicketAction.RELEASE, "agent-a", null, "换人处理");

        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getAssignee()).isNull();
    }

    @Test
    void reopenConflictingWithAnotherOpenTicketIsRejected() {
        when(ticketMapper.selectById(1L)).thenReturn(ticket(1L, TicketStatus.RESOLVED, "agent-a", 2));
        when(ticketMapper.updateWithVersion(any(), eq(2))).thenThrow(new DuplicateKeyException("uk_open_conversation"));

        assertThatThrownBy(() -> service.transition(1L, TicketAction.REOPEN, "agent-a", null, null))
                .isInstanceOf(TicketConflictException.class)
                .hasMessageContaining("未结工单");
    }

    @Test
    void missingTicketIs404() {
        assertThatThrownBy(() -> service.transition(99L, TicketAction.CLAIM, "agent-a", null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void escalationCreatesTicketAndCreateEvent() {
        when(ticketMapper.insert(any(TicketEntity.class))).thenAnswer(invocation -> {
            TicketEntity t = invocation.getArgument(0);
            t.setId(7L);
            return 1;
        });

        TicketService.EscalationResult result = service.openForEscalation(escalation("c1"));

        assertThat(result.created()).isTrue();
        assertThat(result.ticket().getId()).isEqualTo(7L);
        assertThat(result.ticket().getStatus()).isEqualTo("PENDING");
        ArgumentCaptor<TicketEventEntity> event = ArgumentCaptor.forClass(TicketEventEntity.class);
        verify(eventMapper).insert(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo("CREATE");
        assertThat(event.getValue().getOperator()).isEqualTo("system");
    }

    @Test
    void secondEscalationForSameConversationReturnsExistingTicket() {
        doThrow(new DuplicateKeyException("uk_open_conversation")).when(ticketMapper).insert(any(TicketEntity.class));
        when(ticketMapper.selectOpenByConversation("c1")).thenReturn(ticket(3L, TicketStatus.IN_PROGRESS, "agent-a", 1));

        TicketService.EscalationResult result = service.openForEscalation(escalation("c1"));

        assertThat(result.created()).isFalse();
        assertThat(result.ticket().getId()).isEqualTo(3L);
        verify(eventMapper, never()).insert(any(TicketEventEntity.class));
    }

    private static TicketEntity ticket(long id, TicketStatus status, String assignee, int version) {
        TicketEntity t = new TicketEntity();
        t.setId(id);
        t.setConversationId("c1");
        t.setUserId("u1");
        t.setStatus(status.name());
        t.setPriority("MEDIUM");
        t.setReason("test");
        t.setSummary("test");
        t.setAssignee(assignee);
        t.setVersion(version);
        return t;
    }

    private static Escalation escalation(String conversationId) {
        return new Escalation(conversationId, "u1", "HIGH", "refund", "用户要求人工", "用户：退款\nAI：已为您转人工", "r1");
    }
}

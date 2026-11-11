package com.echomind.ticket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketActionTest {

    /** 状态机全表：每个"操作 × 当前状态"是否允许。 */
    @ParameterizedTest(name = "{0} 在 {1} 状态下 → 允许={2}")
    @CsvSource({
            "CLAIM,   PENDING,     true",
            "CLAIM,   IN_PROGRESS, false",
            "CLAIM,   RESOLVED,    false",
            "CLAIM,   CLOSED,      false",
            "RELEASE, IN_PROGRESS, true",
            "RELEASE, PENDING,     false",
            "RESOLVE, IN_PROGRESS, true",
            "RESOLVE, PENDING,     false",
            "RESOLVE, RESOLVED,    false",
            "CLOSE,   PENDING,     true",
            "CLOSE,   RESOLVED,    true",
            "CLOSE,   IN_PROGRESS, false",
            "CLOSE,   CLOSED,      false",
            "REOPEN,  RESOLVED,    true",
            "REOPEN,  CLOSED,      false",
            "REOPEN,  PENDING,     false"
    })
    void transitionTable(TicketAction action, TicketStatus from, boolean allowed) {
        assertThat(action.canApplyTo(from)).isEqualTo(allowed);
    }

    @Test
    void closedIsFinal() {
        for (TicketAction action : TicketAction.values()) {
            assertThat(action.canApplyTo(TicketStatus.CLOSED)).as(action.name()).isFalse();
        }
    }

    @Test
    void onlyAssigneeCanReleaseOrResolve() {
        assertThat(TicketAction.RELEASE.assigneeOnly()).isTrue();
        assertThat(TicketAction.RESOLVE.assigneeOnly()).isTrue();
        assertThat(TicketAction.CLAIM.assigneeOnly()).isFalse();
    }

    @Test
    void parseIsCaseInsensitiveAndRejectsUnknown() {
        assertThat(TicketAction.parse("claim")).isEqualTo(TicketAction.CLAIM);
        assertThat(TicketAction.parse(" Resolve ")).isEqualTo(TicketAction.RESOLVE);
        assertThatThrownBy(() -> TicketAction.parse("delete")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openStatuses() {
        assertThat(TicketStatus.PENDING.isOpen()).isTrue();
        assertThat(TicketStatus.IN_PROGRESS.isOpen()).isTrue();
        assertThat(TicketStatus.RESOLVED.isOpen()).isFalse();
        assertThat(TicketStatus.CLOSED.isOpen()).isFalse();
    }
}

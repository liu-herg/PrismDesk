package com.echomind.ticket;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 并发冲突或状态不允许，对外表现为 HTTP 409。 */
public class TicketConflictException extends ResponseStatusException {

    public TicketConflictException(String reason) {
        super(HttpStatus.CONFLICT, reason);
    }
}

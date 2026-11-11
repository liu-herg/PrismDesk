package com.echomind.lock;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 同一会话的上一条消息还没处理完，等待超时。对外表现为 HTTP 429。 */
public class ConversationBusyException extends ResponseStatusException {

    public ConversationBusyException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "上一条消息还在处理中，请稍后再试");
    }
}

package com.echomind.notify;

import com.echomind.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Tag(name = "实时通知")
public class NotificationController {

    private final NotificationHub hub;

    public NotificationController(NotificationHub hub) {
        this.hub = hub;
    }

    @GetMapping(value = "/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "工单通知（SSE）", description = "坐席收到所有工单变化；客户只收到自己工单的变化。")
    public SseEmitter stream(@AuthenticationPrincipal Jwt jwt) {
        return hub.subscribe(CurrentUser.of(jwt));
    }
}

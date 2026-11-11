package com.echomind.notify;

import com.echomind.auth.CurrentUser;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 本实例上的 SSE 连接（每个打开的浏览器页面一个）。收到通知后按角色过滤再推送：
 * 坐席和管理员收到所有工单变化，客户只收到自己工单的变化。
 *
 * <p>为什么用 SSE 不用 WebSocket：这里只需要服务端单向推送；SSE 走普通 HTTP，
 * 能直接复用 Spring Security 的 Bearer 令牌鉴权，断线后浏览器端重连也简单。
 */
@Component
public class NotificationHub {

    private static final Logger log = LoggerFactory.getLogger(NotificationHub.class);
    /** 连接最长保持 30 分钟，到期由前端重连（顺便用上最新的访问令牌） */
    private static final long TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);

    private final Map<SseEmitter, CurrentUser> subscribers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public NotificationHub() {
        // 定时发一行注释，防止中间的代理（Nginx 等）因为长时间没有数据把连接断掉
        heartbeat.scheduleAtFixedRate(this::ping, 20, 20, TimeUnit.SECONDS);
    }

    public SseEmitter subscribe(CurrentUser user) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        subscribers.put(emitter, user);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        send(emitter, SseEmitter.event().name("ready").data(Map.of("username", user.username()), MediaType.APPLICATION_JSON));
        return emitter;
    }

    public void deliver(TicketNotice notice) {
        subscribers.forEach((emitter, user) -> {
            if (user.isStaff() || user.username().equals(notice.userId())) {
                send(emitter, SseEmitter.event().name("ticket").data(notice, MediaType.APPLICATION_JSON));
            }
        });
    }

    public int connections() {
        return subscribers.size();
    }

    private void ping() {
        subscribers.keySet().forEach(e -> send(e, SseEmitter.event().comment("ping")));
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            // 浏览器已经关掉页面
            subscribers.remove(emitter);
            log.debug("SSE 连接已断开: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        subscribers.keySet().forEach(SseEmitter::complete);
    }
}

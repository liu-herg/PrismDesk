package com.echomind.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工单变化通过 Redis Pub/Sub 广播给所有实例，每个实例再推给连在自己身上的浏览器（{@link NotificationHub}）。
 * 只有一个实例时直接本地推送也行，但浏览器的 SSE 连接可能连在任何一个实例上，所以要先广播。
 *
 * <p>调用时机：业务方法（带 @Transactional）返回之后才调用，此时事务已经提交，
 * 不会出现"通知发出去了、数据却回滚了"。发送失败只记日志：通知是锦上添花，不能影响工单操作本身。
 */
@Component
public class TicketNotifier {

    static final String TOPIC = "prismdesk:ticket-events";
    private static final Logger log = LoggerFactory.getLogger(TicketNotifier.class);

    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final NotificationHub hub;
    private RTopic topic;

    public TicketNotifier(RedissonClient redisson, ObjectMapper objectMapper, NotificationHub hub) {
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.hub = hub;
    }

    @PostConstruct
    void subscribe() {
        topic = redisson.getTopic(TOPIC, StringCodec.INSTANCE);
        topic.addListener(String.class, (channel, message) -> {
            try {
                hub.deliver(objectMapper.readValue(message, TicketNotice.class));
            } catch (Exception e) {
                log.warn("解析工单通知失败: {}", e.getMessage());
            }
        });
    }

    public void publish(TicketNotice notice) {
        try {
            topic.publish(objectMapper.writeValueAsString(notice));
        } catch (Exception e) {
            // Redis 不可用：至少推给本实例上的连接
            log.warn("广播工单通知失败，只推送本实例: {}", e.getMessage());
            hub.deliver(notice);
        }
    }
}

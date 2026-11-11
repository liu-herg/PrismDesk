package com.echomind.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 防暴力破解：同一个用户名连续输错 N 次后锁定一段时间。计数放在 Redis，多实例共享，到期自动解锁。
 * 按用户名而不是按 IP 计数：攻击者换 IP 很容易，但要猜的总是同一个账号。
 */
@Component
public class LoginAttemptGuard {

    static final String KEY = "auth:fail:";

    private final StringRedisTemplate redis;
    private final AuthProperties properties;

    public LoginAttemptGuard(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public boolean isLocked(String username) {
        String count = redis.opsForValue().get(key(username));
        return count != null && Long.parseLong(count) >= properties.maxLoginFailures();
    }

    /** @return 记录后还剩几次机会 */
    public long recordFailure(String username) {
        String key = key(username);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            // 第一次失败时开始计时：锁定窗口从第一次输错算起
            redis.expire(key, properties.lockDuration());
        }
        return Math.max(0, properties.maxLoginFailures() - (count == null ? 0 : count));
    }

    public void reset(String username) {
        redis.delete(key(username));
    }

    public long lockMinutes() {
        return Math.max(1, properties.lockDuration().toMinutes());
    }

    private static String key(String username) {
        return KEY + username.toLowerCase(Locale.ROOT);
    }
}

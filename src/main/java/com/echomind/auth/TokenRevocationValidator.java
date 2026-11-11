package com.echomind.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * JWT 本身是无状态的，签发出去就收不回来。这里用 Redis 补上"吊销"：
 * <ul>
 *   <li>登出：把这个令牌的 jti 放进黑名单，过期时间和令牌一致，到期自动清理</li>
 *   <li>停用账号 / 退出所有设备：记下"这个时间点之前签发的令牌都作废"</li>
 * </ul>
 * 每个请求多两次 Redis 读，换来"随时能让令牌失效"。
 */
@Component
public class TokenRevocationValidator implements OAuth2TokenValidator<Jwt> {

    static final String REVOKED_JTI = "auth:revoked:";
    static final String REVOKED_BEFORE = "auth:revoked-before:";

    private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "令牌已失效，请重新登录", null);

    private final StringRedisTemplate redis;

    public TokenRevocationValidator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getId() != null && Boolean.TRUE.equals(redis.hasKey(REVOKED_JTI + jwt.getId()))) {
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
        String before = redis.opsForValue().get(REVOKED_BEFORE + jwt.getSubject());
        Instant issuedAt = jwt.getIssuedAt();
        // JWT 的签发时间只精确到秒，用 <=：和停用发生在同一秒签发的令牌也作废
        if (before != null && issuedAt != null && issuedAt.getEpochSecond() <= Long.parseLong(before)) {
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
        return OAuth2TokenValidatorResult.success();
    }
}

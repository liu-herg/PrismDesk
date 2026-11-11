package com.echomind.auth;

import com.echomind.store.UserEntity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * 两种令牌：
 * <ul>
 *   <li>访问令牌：JWT，15 分钟。每个请求带上，服务端只验签不查库</li>
 *   <li>刷新令牌：随机字符串，7 天，只用来换新的访问令牌。Redis 里只存它的 SHA-256，
 *       就算 Redis 数据泄露也拿不到可用的令牌；每用一次就作废并换一个新的（轮换）</li>
 * </ul>
 */
@Service
public class TokenService {

    static final String REFRESH = "auth:refresh:";
    static final String USER_REFRESH = "auth:user-refresh:";

    private final JwtEncoder encoder;
    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, StringRedisTemplate redis, AuthProperties properties) {
        this.encoder = encoder;
        this.redis = redis;
        this.properties = properties;
    }

    public TokenPair issue(UserEntity user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(user.getUsername())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("role", user.getRole())
                .claim("name", user.getDisplayName())
                .build();
        String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = sha256(refresh);
        Duration ttl = properties.refreshTtl();
        redis.opsForValue().set(REFRESH + hash, user.getUsername(), ttl);
        // 记下这个用户名下有哪些刷新令牌，停用账号时一次性作废
        redis.opsForSet().add(USER_REFRESH + user.getUsername(), hash);
        redis.expire(USER_REFRESH + user.getUsername(), ttl);
        return new TokenPair(access, expiresAt, refresh, ttl.toSeconds());
    }

    /**
     * 用刷新令牌换身份：GETDEL 原子地"读出并删除"，同一个刷新令牌并发使用时只有一次能成功，
     * 被盗用的旧令牌在正主刷新过之后也就作废了。
     *
     * @return 用户名；令牌无效、过期或已经用过时返回 null
     */
    public String consumeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        String hash = sha256(refreshToken);
        String username = redis.opsForValue().getAndDelete(REFRESH + hash);
        if (username != null) {
            redis.opsForSet().remove(USER_REFRESH + username, hash);
        }
        return username;
    }

    /** 登出：当前访问令牌进黑名单（只保留到它本来的过期时间），刷新令牌删除。 */
    public void revoke(Jwt accessToken, String refreshToken) {
        if (accessToken != null && accessToken.getId() != null && accessToken.getExpiresAt() != null) {
            Duration left = Duration.between(Instant.now(), accessToken.getExpiresAt());
            if (!left.isNegative() && !left.isZero()) {
                redis.opsForValue().set(TokenRevocationValidator.REVOKED_JTI + accessToken.getId(), "1", left);
            }
        }
        consumeRefreshToken(refreshToken);
    }

    /** 停用账号：此刻之前签发的所有访问令牌作废，所有刷新令牌删除。 */
    public void revokeAll(String username) {
        redis.opsForValue().set(TokenRevocationValidator.REVOKED_BEFORE + username,
                String.valueOf(Instant.now().getEpochSecond()), properties.accessTtl());
        Set<String> hashes = redis.opsForSet().members(USER_REFRESH + username);
        if (hashes != null) {
            hashes.forEach(h -> redis.delete(REFRESH + h));
        }
        redis.delete(USER_REFRESH + username);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record TokenPair(String accessToken, Instant accessExpiresAt, String refreshToken, long refreshExpiresIn) {
    }
}

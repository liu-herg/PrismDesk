package com.echomind.auth;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.echomind.store.StoreConfig;
import com.echomind.store.UserEntity;
import com.echomind.store.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录流程，用真实的 MySQL（账号表）和 Redis（刷新令牌、吊销名单、失败计数）：
 * <pre>
 * docker run --rm --network prismdesk_default -e IT_MYSQL_URL=jdbc:mysql://mysql:3306/prismdesk
 *   -e IT_REDIS_HOST=redis -e IT_REDIS_PASSWORD=echomind123 ... mvn test
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "IT_MYSQL_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IT_REDIS_HOST", matches = ".+")
@SpringBootTest(classes = {AuthService.class, TokenService.class, LoginAttemptGuard.class,
        TokenRevocationValidator.class, JwtConfig.class, StoreConfig.class})
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class,
        RedisAutoConfiguration.class
})
class AuthFlowTest {

    private static final String PASSWORD = "secret-123";

    @Autowired
    private AuthService authService;
    @Autowired
    private JwtDecoder jwtDecoder;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate redis;

    private final List<String> created = new ArrayList<>();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        String url = System.getenv("IT_MYSQL_URL");
        String separator = url.contains("?") ? "&" : "?";
        registry.add("spring.datasource.url", () -> url + separator
                + "useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("IT_MYSQL_USER", "prismdesk"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("IT_MYSQL_PASSWORD", "prismdesk123"));
        registry.add("spring.data.redis.host", () -> System.getenv("IT_REDIS_HOST"));
        registry.add("spring.data.redis.port", () -> System.getenv().getOrDefault("IT_REDIS_PORT", "6379"));
        registry.add("spring.data.redis.password", () -> System.getenv().getOrDefault("IT_REDIS_PASSWORD", ""));
    }

    @AfterEach
    void cleanUp() {
        if (!created.isEmpty()) {
            userMapper.delete(new QueryWrapper<UserEntity>().in("username", created));
            created.forEach(u -> redis.delete(LoginAttemptGuard.KEY + u));
            created.clear();
        }
    }

    @Test
    void registeredCustomerGetsAWorkingAccessToken() {
        String name = newName();
        AuthService.LoginResult result = authService.register(name, PASSWORD, "测试客户");

        Jwt jwt = jwtDecoder.decode(result.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(name);
        assertThat(jwt.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(result.user().displayName()).isEqualTo("测试客户");
        // 同名注册：409
        assertThatThrownBy(() -> authService.register(name, PASSWORD, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenForTheRightPassword() {
        String name = newName();
        authService.register(name, PASSWORD, null);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(name, "wrong"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        }
        assertThatThrownBy(() -> authService.login(name, PASSWORD))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(429));
    }

    @Test
    void refreshTokenIsSingleUse() {
        String name = newName();
        String first = authService.register(name, PASSWORD, null).refreshToken();

        AuthService.LoginResult renewed = authService.refresh(first);
        assertThat(renewed.refreshToken()).isNotEqualTo(first);
        assertThatThrownBy(() -> authService.refresh(first))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        assertThat(authService.refresh(renewed.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void concurrentRefreshWithTheSameTokenSucceedsOnlyOnce() throws Exception {
        String name = newName();
        String token = authService.register(name, PASSWORD, null).refreshToken();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        authService.refresh(token);
                        return "成功";
                    } catch (ResponseStatusException e) {
                        return "拒绝";
                    }
                }));
            }
            start.countDown();
            Map<String, Integer> counts = new TreeMap<>();
            for (Future<String> f : futures) {
                counts.merge(f.get(30, TimeUnit.SECONDS), 1, Integer::sum);
            }
            System.out.printf("[刷新令牌] 同一个刷新令牌 %d 个并发请求：%s%n", threads, counts);
            assertThat(counts).containsEntry("成功", 1).containsEntry("拒绝", threads - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void logoutRevokesTheAccessTokenImmediately() {
        String name = newName();
        AuthService.LoginResult login = authService.register(name, PASSWORD, null);
        Jwt jwt = jwtDecoder.decode(login.accessToken());

        authService.logout(jwt, login.refreshToken());

        // 访问令牌本来还有 15 分钟有效期，登出后立即不能用
        assertThatThrownBy(() -> jwtDecoder.decode(login.accessToken())).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> authService.refresh(login.refreshToken())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void disablingAnAccountKillsItsExistingTokens() {
        String name = newName();
        AuthService.LoginResult login = authService.register(name, PASSWORD, null);
        long id = userMapper.selectByUsername(name).getId();

        authService.setEnabled(id, false, "admin");

        assertThatThrownBy(() -> jwtDecoder.decode(login.accessToken())).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> authService.refresh(login.refreshToken())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> authService.login(name, PASSWORD))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void passwordsAreStoredAsBcryptHashes() {
        String name = newName();
        authService.register(name, PASSWORD, null);
        String hash = userMapper.selectByUsername(name).getPasswordHash();
        assertThat(hash).startsWith("$2a$").doesNotContain(PASSWORD);
    }

    private String newName() {
        String name = "it_" + UUID.randomUUID().toString().substring(0, 8);
        created.add(name);
        return name;
    }
}

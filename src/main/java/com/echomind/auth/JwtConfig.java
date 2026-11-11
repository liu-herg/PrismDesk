package com.echomind.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** 令牌的签发与校验。和 Web 安全配置分开，方便集成测试只加载这一部分。 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

    static final String ISSUER = "prismdesk";
    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    private final SecretKey key;

    public JwtConfig(AuthProperties properties) {
        byte[] secret = properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("prismdesk.auth.jwt-secret 至少需要 32 字节（HS256）");
        }
        if (properties.jwtSecret().startsWith("prismdesk-local-dev-secret")) {
            log.warn("正在使用默认的 JWT 签名密钥，只适合本地开发；部署时请设置环境变量 JWT_SECRET");
        }
        this.key = new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /** 校验：签名 + 过期时间 + 签发者，再加上 Redis 里的吊销名单（登出、停用账号）。 */
    @Bean
    public JwtDecoder jwtDecoder(TokenRevocationValidator revocationValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), revocationValidator));
        return decoder;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}

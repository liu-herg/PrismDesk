package com.echomind.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param jwtSecret         HS256 签名密钥，至少 32 字节。生产环境必须用环境变量 JWT_SECRET 覆盖
 * @param accessTtl         访问令牌有效期。短一些：泄露后的危害窗口小
 * @param refreshTtl        刷新令牌有效期
 * @param maxLoginFailures  连续输错密码多少次后锁定
 * @param lockDuration      锁定多久
 * @param seedDemoAccounts  启动时创建演示账号（已存在则跳过）
 * @param demoPassword      演示账号的密码
 */
@ConfigurationProperties("prismdesk.auth")
public record AuthProperties(
        @DefaultValue("prismdesk-local-dev-secret-change-me-0123456789") String jwtSecret,
        @DefaultValue("15m") Duration accessTtl,
        @DefaultValue("7d") Duration refreshTtl,
        @DefaultValue("5") int maxLoginFailures,
        @DefaultValue("15m") Duration lockDuration,
        @DefaultValue("true") boolean seedDemoAccounts,
        @DefaultValue("demo123") String demoPassword) {
}

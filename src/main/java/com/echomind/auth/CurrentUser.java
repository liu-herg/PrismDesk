package com.echomind.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/** 从访问令牌里取出的当前用户。接口里的身份一律以它为准，不再相信请求参数里的 user_id / operator。 */
public record CurrentUser(String username, Role role, String displayName) {

    public static CurrentUser of(Jwt jwt) {
        return new CurrentUser(jwt.getSubject(), Role.valueOf(jwt.getClaimAsString("role")), jwt.getClaimAsString("name"));
    }

    public boolean isStaff() {
        return role.isStaff();
    }
}

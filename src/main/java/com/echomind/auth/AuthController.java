package com.echomind.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "登录", description = "注册、登录、刷新令牌、登出")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "注册客户账号", description = "只能注册客户；注册成功直接返回令牌。")
    public AuthService.LoginResult register(@RequestBody RegisterRequest body) {
        return authService.register(body.username(), body.password(), body.displayName());
    }

    @PostMapping("/login")
    @Operation(summary = "登录", description = "连续输错 5 次锁定 15 分钟。")
    public AuthService.LoginResult login(@RequestBody LoginRequest body) {
        return authService.login(body.username(), body.password());
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌", description = "用刷新令牌换一对新令牌，旧的刷新令牌立即作废。")
    public AuthService.LoginResult refresh(@RequestBody RefreshRequest body) {
        return authService.refresh(body.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "登出", description = "当前访问令牌和刷新令牌立即失效。")
    public void logout(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) RefreshRequest body) {
        authService.logout(jwt, body == null ? null : body.refreshToken());
    }

    @GetMapping("/me")
    @Operation(summary = "当前用户")
    public UserView me(@AuthenticationPrincipal Jwt jwt) {
        return authService.me(jwt);
    }

    public record LoginRequest(String username, String password) {
    }

    public record RegisterRequest(String username, String password, String displayName) {
    }

    public record RefreshRequest(String refreshToken) {
    }
}

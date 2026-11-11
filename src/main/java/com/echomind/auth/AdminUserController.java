package com.echomind.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/** 账号管理，只有管理员能访问（规则在 SecurityConfig 里按路径配置）。 */
@RestController
@RequestMapping("/admin/users")
@Tag(name = "账号管理")
public class AdminUserController {

    private final AuthService authService;

    public AdminUserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    public List<UserView> list() {
        return authService.listUsers();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建账号", description = "管理员可以创建坐席、管理员和客户账号。")
    public UserView create(@RequestBody CreateUserRequest body) {
        Role role;
        try {
            role = Role.valueOf(String.valueOf(body.role()).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色只能是 CUSTOMER / AGENT / ADMIN");
        }
        return authService.createUser(body.username(), body.password(), body.displayName(), role);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "启用 / 停用账号", description = "停用后该账号已签发的令牌立即失效。")
    public UserView update(@PathVariable long id, @RequestBody UpdateUserRequest body,
                           @AuthenticationPrincipal Jwt jwt) {
        if (body.enabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled 不能为空");
        }
        return authService.setEnabled(id, body.enabled(), jwt.getSubject());
    }

    public record CreateUserRequest(String username, String password, String displayName, String role) {
    }

    public record UpdateUserRequest(Boolean enabled) {
    }
}

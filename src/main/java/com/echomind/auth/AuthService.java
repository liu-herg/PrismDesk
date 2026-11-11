package com.echomind.auth;

import com.echomind.store.UserEntity;
import com.echomind.store.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final LoginAttemptGuard guard;
    /** 用户名不存在时也做一次同样耗时的 BCrypt 比对，避免通过响应时间判断用户名是否存在 */
    private final String dummyHash;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, TokenService tokenService,
                       LoginAttemptGuard guard) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.guard = guard;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    /** 自助注册只能注册客户；坐席和管理员由管理员创建。注册成功直接登录。 */
    public LoginResult register(String username, String password, String displayName) {
        UserEntity user = create(username, password, displayName, Role.CUSTOMER);
        return issue(user);
    }

    public LoginResult login(String username, String password) {
        String name = username == null ? "" : username.trim();
        if (guard.isLocked(name)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "密码错误次数过多，请 " + guard.lockMinutes() + " 分钟后再试");
        }
        UserEntity user = name.isEmpty() ? null : userMapper.selectByUsername(name);
        boolean matches = passwordEncoder.matches(password == null ? "" : password,
                user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches) {
            long left = guard.recordFailure(name);
            // 不区分"用户名不存在"和"密码错误"，避免被用来探测有哪些账号
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    left > 0 ? "用户名或密码错误，还可以再试 " + left + " 次" : "密码错误次数过多，请 " + guard.lockMinutes() + " 分钟后再试");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用，请联系管理员");
        }
        guard.reset(name);
        userMapper.updateLastLogin(user.getId(), LocalDateTime.now());
        return issue(user);
    }

    public LoginResult refresh(String refreshToken) {
        String username = tokenService.consumeRefreshToken(refreshToken);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        UserEntity user = userMapper.selectByUsername(username);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不可用，请重新登录");
        }
        return issue(user);
    }

    public void logout(Jwt accessToken, String refreshToken) {
        tokenService.revoke(accessToken, refreshToken);
    }

    public UserView me(Jwt jwt) {
        UserEntity user = userMapper.selectByUsername(jwt.getSubject());
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不存在");
        }
        return UserView.of(user);
    }

    // ---------------------------------------------------------------- 管理员

    public List<UserView> listUsers() {
        return userMapper.selectAllOrdered().stream().map(UserView::of).toList();
    }

    public UserView createUser(String username, String password, String displayName, Role role) {
        return UserView.of(create(username, password, displayName, role));
    }

    /** 停用后立刻失效：已经签发的访问令牌和刷新令牌全部作废，不用等 15 分钟过期。 */
    public UserView setEnabled(long id, boolean enabled, String operator) {
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
        }
        if (!enabled && user.getUsername().equals(operator)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能停用自己的账号");
        }
        userMapper.updateEnabled(id, enabled);
        if (!enabled) {
            tokenService.revokeAll(user.getUsername());
        }
        // 重新启用时不清除"之前签发的令牌作废"的标记：否则停用前签发、还没过期的旧令牌会重新生效。
        // 这个标记 15 分钟（一个访问令牌的有效期）后自动过期
        user.setEnabled(enabled);
        return UserView.of(user);
    }

    UserEntity create(String username, String password, String displayName, Role role) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME.matcher(name).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名只能包含字母、数字、下划线和短横线，3 到 32 位");
        }
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码长度需要 6 到 64 位");
        }
        UserEntity user = new UserEntity();
        user.setUsername(name);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName == null || displayName.isBlank() ? name : displayName.trim());
        user.setRole(role.name());
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 直接插入、靠唯一索引判重：两个人同时注册同一个用户名，只有一个能成功
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名 " + name + " 已被占用");
        }
        return user;
    }

    private LoginResult issue(UserEntity user) {
        TokenService.TokenPair pair = tokenService.issue(user);
        return new LoginResult(pair.accessToken(), pair.accessExpiresAt().getEpochSecond(), pair.refreshToken(),
                pair.refreshExpiresIn(), UserView.of(user));
    }

    /** @param accessExpiresAt 访问令牌过期时间（Unix 秒），前端据此提前刷新 */
    public record LoginResult(String accessToken, long accessExpiresAt, String refreshToken, long refreshExpiresIn,
                              UserView user) {
    }
}

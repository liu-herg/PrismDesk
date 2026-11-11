package com.echomind.auth;

import com.echomind.store.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 启动时创建演示账号（已存在就跳过）。用户名和之前测试数据里的 user_id / 坐席 ID 一致，
 * 登录后能直接看到以前的会话和工单。用 SmartInitializingSingleton 而不是 @PostConstruct：保证 Flyway 已经建好表。
 */
@Component
public class DemoAccountInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountInitializer.class);

    private record Demo(String username, String displayName, Role role) {
    }

    private static final List<Demo> ACCOUNTS = List.of(
            new Demo("u1001", "张敏", Role.CUSTOMER),
            new Demo("u1002", "李响", Role.CUSTOMER),
            new Demo("agent-01", "坐席小周", Role.AGENT),
            new Demo("agent-02", "坐席小林", Role.AGENT),
            new Demo("admin", "管理员", Role.ADMIN));

    private final AuthProperties properties;
    private final AuthService authService;
    private final UserMapper userMapper;

    public DemoAccountInitializer(AuthProperties properties, AuthService authService, UserMapper userMapper) {
        this.properties = properties;
        this.authService = authService;
        this.userMapper = userMapper;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.seedDemoAccounts()) {
            return;
        }
        int created = 0;
        for (Demo demo : ACCOUNTS) {
            if (userMapper.selectByUsername(demo.username()) != null) {
                continue;
            }
            try {
                authService.create(demo.username(), properties.demoPassword(), demo.displayName(), demo.role());
                created++;
            } catch (ResponseStatusException e) {
                // 另一个实例同时在创建：唯一索引兜底，忽略
            }
        }
        if (created > 0) {
            log.info("已创建 {} 个演示账号（密码 {}）：u1001 / u1002 客户，agent-01 / agent-02 坐席，admin 管理员",
                    created, properties.demoPassword());
        }
    }
}

-- 登录账号 + 每条回复的处理轨迹

-- 账号。username 就是业务里用的用户标识（会话的 user_id、工单的 assignee），
-- 所以 V1/V2 里已有的数据（u1001、agent-01 等）建了同名账号后可以直接看到。
CREATE TABLE app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt',
    display_name  VARCHAR(64)  NOT NULL,
    role          VARCHAR(16)  NOT NULL COMMENT 'CUSTOMER / AGENT / ADMIN',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    created_at    DATETIME(3)  NOT NULL,
    last_login_at DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='登录账号';

-- 回复的处理轨迹：意图置信度、路由、各环节耗时、降级环节、校验结果。
-- 坐席查看历史对话时，每条回复都能看到"它是怎么来的"。
ALTER TABLE chat_message
    ADD COLUMN trace JSON NULL COMMENT '处理轨迹，只有客服回复有',
    ADD KEY idx_request (request_id);

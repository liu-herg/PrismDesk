-- 转人工工单

CREATE TABLE ticket (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id      VARCHAR(64)  NOT NULL,
    user_id              VARCHAR(64)  NOT NULL,
    status               VARCHAR(16)  NOT NULL COMMENT 'PENDING / IN_PROGRESS / RESOLVED / CLOSED',
    priority             VARCHAR(16)  NOT NULL COMMENT '来自意图识别的紧急程度：LOW / MEDIUM / HIGH / CRITICAL',
    intent               VARCHAR(32)  NULL,
    reason               VARCHAR(255) NOT NULL COMMENT '触发转人工的原因',
    summary              TEXT         NOT NULL COMMENT '建单时用户的问题和 AI 的回答，方便坐席快速了解情况',
    source_request_id    VARCHAR(32)  NULL COMMENT '触发建单的那一轮对话',
    assignee             VARCHAR(64)  NULL COMMENT '当前处理的坐席',
    version              INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次状态变更 +1',
    created_at           DATETIME(3)  NOT NULL,
    updated_at           DATETIME(3)  NOT NULL,
    assigned_at          DATETIME(3)  NULL,
    resolved_at          DATETIME(3)  NULL,
    closed_at            DATETIME(3)  NULL,
    -- 同一个会话同时只能有一张未结工单。MySQL 没有"部分唯一索引"，
    -- 用生成列实现：未结时取会话 ID，已结时为 NULL；唯一索引允许多个 NULL，所以已结的工单不受限制。
    open_conversation_id VARCHAR(64) GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'IN_PROGRESS') THEN conversation_id END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_open_conversation (open_conversation_id),
    -- 工单列表：按状态筛选，按 id 倒序游标翻页
    KEY idx_status_id (status, id),
    -- "我的工单"：按坐席筛选
    KEY idx_assignee_status (assignee, status),
    KEY idx_conversation (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='转人工工单';

-- 工单操作记录：每次状态变化一条，和状态更新在同一个事务里写入
CREATE TABLE ticket_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    ticket_id   BIGINT       NOT NULL,
    action      VARCHAR(16)  NOT NULL COMMENT 'CREATE / CLAIM / RELEASE / RESOLVE / CLOSE / REOPEN',
    from_status VARCHAR(16)  NULL,
    to_status   VARCHAR(16)  NOT NULL,
    operator    VARCHAR(64)  NOT NULL COMMENT '操作人；系统自动建单时为 system',
    note        VARCHAR(500) NULL,
    created_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ticket_id (ticket_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='工单操作记录';

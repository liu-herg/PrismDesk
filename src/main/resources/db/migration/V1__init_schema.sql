-- PrismDesk 初始表结构
-- Redis 只保存"最近 N 条"工作记忆（会过期），MySQL 是对话、知识库和长期记忆的权威存储。

-- 会话：一次客服对话
CREATE TABLE conversation (
    id              VARCHAR(64)  NOT NULL COMMENT '会话 ID（与接口里的 conv_id 一致）',
    user_id         VARCHAR(64)  NOT NULL,
    title           VARCHAR(100) NOT NULL DEFAULT '' COMMENT '首条用户消息截断，用于列表展示',
    message_count   INT          NOT NULL DEFAULT 0,
    last_message_at DATETIME(3)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    -- 查询"某用户最近的会话"：等值列在前，排序列在后
    KEY idx_user_last (user_id, last_message_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='客服会话';

-- 消息：每轮对话写两条（user / assistant）
CREATE TABLE chat_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL COMMENT 'USER / ASSISTANT',
    content         TEXT         NOT NULL,
    request_id      VARCHAR(32)  NULL COMMENT '同一轮的两条消息共享 request_id，便于和调用轨迹对齐',
    intent          VARCHAR(32)  NULL,
    agent_type      VARCHAR(32)  NULL,
    escalated       TINYINT(1)   NOT NULL DEFAULT 0,
    latency_ms      INT          NULL,
    created_at      DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    -- 历史消息用游标分页：WHERE conversation_id = ? AND id < ? ORDER BY id DESC LIMIT ?
    KEY idx_conv_id (conversation_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='会话消息';

-- 知识库切片：文档切分后的每一段
CREATE TABLE knowledge_chunk (
    id          CHAR(32)     NOT NULL COMMENT 'md5(标题 + 序号 + 内容前 50 字)，重复导入时幂等覆盖',
    doc_title   VARCHAR(200) NOT NULL,
    chunk_index INT          NOT NULL,
    content     TEXT         NOT NULL,
    metadata    JSON         NULL,
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_doc_title (doc_title)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='知识库切片';

-- 情景记忆：工作记忆被压缩时，把旧消息的摘要沉淀到这里
CREATE TABLE episodic_memory (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    summary         TEXT        NOT NULL,
    full_text       MEDIUMTEXT  NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='情景记忆';

-- 用户画像：每个用户一行，异步更新时整行覆盖
CREATE TABLE user_profile (
    user_id    VARCHAR(64) NOT NULL,
    profile    JSON        NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户画像';

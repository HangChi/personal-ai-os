-- ============================================================
-- V2: 阶段 1 —— 聊天会话与消息
--
-- 设计说明见 specs/002-ai-chat/data-model.md。
-- 关键约定：
--   1) sequence_no 是消息顺序的唯一稳定依据，排序不依赖 created_at（会受同毫秒写入与时钟回拨影响）。
--   2) 不额外为 (conversation_id, sequence_no) 建普通索引 ——
--      下面的 UNIQUE 约束已经创建了同列顺序的索引，重复创建只是浪费写入成本。
-- ============================================================

CREATE TABLE IF NOT EXISTS chat_session (
    conversation_id VARCHAR(64)  PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  chat_session                 IS '聊天会话；conversation_id 同时作为 Spring AI 的会话上下文标识（隔离边界）';
COMMENT ON COLUMN chat_session.title           IS '会话标题；未指定时由首条用户消息前 30 字生成';
COMMENT ON COLUMN chat_session.updated_at      IS '最近活动时间，收到新消息时刷新（FR-019）';

CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGSERIAL    PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    message_type    VARCHAR(20)  NOT NULL,
    content         TEXT         NOT NULL,
    sequence_no     INTEGER      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_chat_message_session
        FOREIGN KEY (conversation_id) REFERENCES chat_session (conversation_id) ON DELETE CASCADE,
    CONSTRAINT uk_chat_message_conversation_sequence
        UNIQUE (conversation_id, sequence_no),
    -- TOOL 为阶段 2（工具调用）预留；阶段 1 只会写入前三种
    CONSTRAINT ck_chat_message_type
        CHECK (message_type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL'))
);

COMMENT ON TABLE  chat_message                IS '聊天消息；按 sequence_no 稳定排序，历史永不裁剪（FR-007）';
COMMENT ON COLUMN chat_message.sequence_no    IS '会话内顺序号，从 1 递增；并发写入冲突由唯一约束暴露，而非静默乱序';

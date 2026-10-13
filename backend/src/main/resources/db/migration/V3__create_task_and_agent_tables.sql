-- ============================================================
-- V3: 阶段 2 —— 任务、待确认操作、操作记录
--
-- 设计说明见 specs/003-task-agent/data-model.md。
-- 关键约定：
--   1) operation_record 一张表同时承担「工具执行审计」与「两层幂等」：
--      每行 = 一次被请求过的操作，outcome 说明它实际发生了什么。
--   2) task_confirmation.payload 用 text 而非 jsonb：只需整体回放，
--      不需要在 SQL 内按字段查询，避免 Hibernate JSON 类型映射的脆性。
-- ============================================================

CREATE TABLE IF NOT EXISTS task (
    id           UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    title        VARCHAR(200) NOT NULL,
    description  TEXT,
    status       VARCHAR(20)  NOT NULL DEFAULT 'TODO',
    priority     VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',
    due_at       TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 取值约束放在库层：模型经由工具传参是最不可控的输入来源，多一层拦截是划算的
    CONSTRAINT ck_task_status   CHECK (status IN ('TODO', 'DOING', 'DONE')),
    CONSTRAINT ck_task_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH'))
);

-- 支撑「按状态筛选 + 按截止时间排序」这一最常用查询形态
CREATE INDEX IF NOT EXISTS idx_task_status_due ON task (status, due_at);

COMMENT ON TABLE  task              IS '个人任务；阶段 4 的日程提醒不依赖此表的 due_at';
COMMENT ON COLUMN task.completed_at IS '不变量：仅当 status = DONE 时非空';

CREATE TABLE IF NOT EXISTS task_confirmation (
    id              UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    token           VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64),
    action          VARCHAR(40)  NOT NULL,
    summary         VARCHAR(500) NOT NULL,
    payload         TEXT         NOT NULL,
    affected_count  INTEGER      NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT uk_task_confirmation_token UNIQUE (token),
    CONSTRAINT ck_task_confirmation_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_task_confirmation_action
        CHECK (action IN ('DELETE_TASK', 'SCHEDULE_TASK', 'SET_PRIORITY'))
);

CREATE INDEX IF NOT EXISTS idx_task_confirmation_pending ON task_confirmation (status, expires_at);

COMMENT ON TABLE  task_confirmation         IS '需人工确认的高风险操作；确认途径只存在于面向人类的接口中';
COMMENT ON COLUMN task_confirmation.summary IS '由服务端依据 payload 生成，不采信模型输出的文本';

CREATE TABLE IF NOT EXISTS operation_record (
    id               BIGSERIAL    PRIMARY KEY,
    idempotency_key  VARCHAR(160) NOT NULL,
    source           VARCHAR(10)  NOT NULL,
    operation        VARCHAR(120) NOT NULL,
    request_digest   VARCHAR(64)  NOT NULL,
    conversation_id  VARCHAR(64),
    turn_id          VARCHAR(64),
    outcome          VARCHAR(24)  NOT NULL,
    duration_ms      INTEGER,
    response_payload TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_operation_record_key UNIQUE (idempotency_key),
    CONSTRAINT ck_operation_record_source  CHECK (source IN ('TOOL', 'HTTP')),
    CONSTRAINT ck_operation_record_outcome
        CHECK (outcome IN ('IN_PROGRESS', 'SUCCESS', 'REQUIRES_CONFIRMATION', 'REJECTED', 'DUPLICATE', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_operation_record_conversation
    ON operation_record (conversation_id, created_at DESC);

COMMENT ON TABLE  operation_record               IS '工具执行审计 + 两层幂等记录；每行代表一次被请求过的操作';
COMMENT ON COLUMN operation_record.request_digest IS '参数 SHA-256；同键不同摘要应判为冲突而非静默执行';
COMMENT ON COLUMN operation_record.turn_id       IS '工具层幂等的范围边界：同一轮内的重复调用才算重复';

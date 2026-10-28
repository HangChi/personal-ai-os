-- ============================================================
-- V27: Agent 规划编排（阶段 22）
--
-- 背景：本项目此前只有「单轮工具循环」——模型自己决定调哪个工具，
-- 边想边做（ReAct 式）。它没有显式计划：复杂任务做到一半失败时，
-- 没人知道还剩几步、哪一步出的问题、要不要换路线。
--
-- 本迁移引入两级结构：
--   agent_plan      一次任务的目标与整体状态
--   agent_plan_step 有序步骤，每步记录状态、绑定到的工具与结果
--
-- 设计要点：
--   1. **一个会话最多一个 ACTIVE 计划**（部分唯一索引保证）。并发建计划时
--      靠数据库而不是应用判断，避免两个标签页各自建一份、交替推进。
--   2. 步骤**只追加不重排**：seq 是稳定标识，工具与前端都用它引用步骤。
--      重规划走「旧计划 ABORTED + 新计划」，保留历史而不是就地改步骤 ——
--      改完就分不清「原计划走到哪」了。
--   3. 不存「计划文本」而存结构化步骤：只有结构化的才能被工具读取、
--      被前端渲染、被失败重规划定位。
--
-- 纪律：已应用的迁移只追加，不改写。V1-V26 零触碰。
-- ============================================================

CREATE TABLE agent_plan (
    id              uuid        PRIMARY KEY,
    conversation_id varchar(64) NOT NULL REFERENCES chat_session (conversation_id) ON DELETE CASCADE,
    goal            text        NOT NULL,
    status          varchar(16) NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT ck_agent_plan_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'ABORTED'))
);

COMMENT ON TABLE agent_plan IS 'Agent 任务计划：一次多步任务的总体目标与状态';
COMMENT ON COLUMN agent_plan.goal IS '用户目标的一句话复述，用于计划面板标题与重规划时重新对齐';
COMMENT ON COLUMN agent_plan.status IS 'ACTIVE=进行中（每会话至多一个）/ COMPLETED=全部步骤走完 / ABORTED=被放弃或被新计划取代';

CREATE TABLE agent_plan_step (
    id          uuid         PRIMARY KEY,
    plan_id     uuid         NOT NULL REFERENCES agent_plan (id) ON DELETE CASCADE,
    seq         integer      NOT NULL,
    title       varchar(200) NOT NULL,
    status      varchar(16)  NOT NULL,
    detail      text,
    tool_name   varchar(120),
    tool_result text,
    error       text,
    started_at  timestamptz,
    finished_at timestamptz,
    CONSTRAINT ck_agent_plan_step_status
        CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'SKIPPED')),
    CONSTRAINT uk_agent_plan_step_seq UNIQUE (plan_id, seq)
);

COMMENT ON TABLE agent_plan_step IS '计划步骤：有序、带状态，并记录该步实际调用到的工具与结果';
COMMENT ON COLUMN agent_plan_step.seq IS '步骤序号（从 1 开始）。稳定标识，工具与前端都用它引用';
COMMENT ON COLUMN agent_plan_step.detail IS '该步的验收说明（模型写，供下一轮对齐「这一步算不算做完」）';
COMMENT ON COLUMN agent_plan_step.tool_name IS '该步实际调用到的工具名（由工具执行外壳自动回填，不依赖模型自报）';
COMMENT ON COLUMN agent_plan_step.tool_result IS '该步绑定到的工具结果摘要（截断存储，完整结果仍在审计表）';
COMMENT ON COLUMN agent_plan_step.error IS '失败原因；非空且 status=FAILED 时前端提示「可以重规划」';

-- 每会话至多一个进行中的计划：并发建计划由数据库挡住
CREATE UNIQUE INDEX uk_agent_plan_active_per_conversation
    ON agent_plan (conversation_id) WHERE status = 'ACTIVE';

-- 计划面板与工具自检都按「会话 + 状态」查
CREATE INDEX idx_agent_plan_conversation ON agent_plan (conversation_id, created_at DESC);

-- 找「哪个步骤正在跑」是每次工具调用的必经路径，单独索引
CREATE INDEX idx_agent_plan_step_running ON agent_plan_step (plan_id, status) WHERE status = 'RUNNING';

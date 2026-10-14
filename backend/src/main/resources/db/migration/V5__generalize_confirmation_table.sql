-- ============================================================
-- V5: 阶段 4 前置 —— 把「任务专用」的确认表泛化为通用表
--
-- 背景：日程删除也要走人工确认，而确认机制（令牌 / 有效期 / 单次性 / 原子占位）
-- 与具体业务无关。表名从 task_confirmation 改为 confirmation，是让名字跟上事实 ——
-- 它现在同时承载任务与日程两类高风险操作。
--
-- 本迁移只做重命名与约束改名：
--   - 不改任何数据，纯 DDL；
--   - CHECK 的取值集合**保持不变**（阶段 4 的日程动作会在后续迁移里追加，
--     避免「枚举值已存在但处理器还没写」导致启动期校验失败）。
-- ============================================================

ALTER TABLE task_confirmation RENAME TO confirmation;

ALTER TABLE confirmation RENAME CONSTRAINT uk_task_confirmation_token TO uk_confirmation_token;

ALTER INDEX idx_task_confirmation_pending RENAME TO idx_confirmation_pending;

ALTER TABLE confirmation DROP CONSTRAINT ck_task_confirmation_status;
ALTER TABLE confirmation ADD CONSTRAINT ck_confirmation_status
    CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED'));

ALTER TABLE confirmation DROP CONSTRAINT ck_task_confirmation_action;
ALTER TABLE confirmation ADD CONSTRAINT ck_confirmation_action
    CHECK (action IN ('DELETE_TASK', 'SCHEDULE_TASK', 'SET_PRIORITY'));

COMMENT ON TABLE confirmation IS
    '需人工确认的高风险操作（跨模块通用）；确认途径只存在于面向人类的接口中，工具集合里没有对应能力';

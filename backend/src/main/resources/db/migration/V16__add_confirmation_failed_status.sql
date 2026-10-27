-- ============================================================
-- V16: 待确认操作新增 FAILED 状态，区分「执行失败」与「用户取消」
--
-- 背景：确认链路由「占位与执行同一事务」改为「占位提交 → 事务外执行 → 独立事务回写」。
-- 改动之后，执行失败时占位已经提交，必须能在事后如实记录结果 ——
-- 而原来的取值集合里没有合适的去处：
--   CANCELLED 表示「用户主动取消」或「目标已不存在」，用它承载系统故障会误导用户；
--   CONFIRMED 会被误读成「已执行」，正是本次要修的那个 bug。
--
-- 一个真实的失败例子：MCP server 在确认前挂掉，handler 抛业务异常。
-- 改前记录停在 CONFIRMED（用户以为已执行，实际一行未改，令牌已作废，操作永久丢失）。
-- 改后停在 FAILED，界面可据此提示「执行失败，目标未变更」。
--
-- 纪律：已应用的迁移只追加，不改写。V1-V15 零触碰。
-- 约束仍是 V5 确立、V7/V8 扩展过的 ck_confirmation_status / ck_confirmation_action。
-- ============================================================

ALTER TABLE confirmation DROP CONSTRAINT ck_confirmation_status;
ALTER TABLE confirmation ADD CONSTRAINT ck_confirmation_status
    CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'FAILED'));

COMMENT ON COLUMN confirmation.status IS
    'PENDING 待确认 / CONFIRMED 已执行 / CANCELLED 用户取消或目标不存在 / EXPIRED 超时 / FAILED 执行失败（目标可能未变更）';

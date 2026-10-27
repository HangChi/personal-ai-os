-- 任务重开时恢复「完成前状态」。
--
-- 为什么需要这一列：`reopen` 原实现无条件写 TODO，把「进行中」静默降级成「待办」，
-- 用户点一下「取消完成」就永久丢掉了 DOING —— 而任务表只有三个状态，
-- 这个信息在实体上没有任何别的地方可恢复。
--
-- 为什么不做成通用审计字段：只需要「上一次非 DONE 状态」，语义单一，
-- 通用历史表会引入查询成本与清理策略，与个人单用户场景不匹配。
--
-- 默认值留给历史数据：已有记录一律填 TODO，它们本来也无法恢复原状态；
-- 新记录由 TaskEntity.setStatus 在进入 DONE 时写入。
ALTER TABLE task
    ADD COLUMN IF NOT EXISTS previous_status VARCHAR(20);

ALTER TABLE task
    ADD CONSTRAINT ck_task_previous_status
        CHECK (previous_status IS NULL OR previous_status IN ('TODO', 'DOING'));

COMMENT ON COLUMN task.previous_status IS
    '进入 DONE 之前的非完成状态；reopen 时据此恢复，NULL 表示回到 TODO';

-- ============================================================
-- V6: 修正 V5 遗漏的主键约束名
--
-- 背景：V5 把 task_confirmation 重命名为 confirmation，但 PostgreSQL 的
-- `ALTER TABLE ... RENAME TO` **不会**连带重命名主键约束 —— 于是出现
-- 「表叫 confirmation，主键却叫 task_confirmation_pkey」的错位。
-- 这种残留名称不影响功能，但会让后来查 schema 的人困惑，属于该修的小债。
--
-- 为什么不直接改 V5：V5 已经应用（本地开发库），修改已应用的迁移会让
-- 校验和与 flyway_schema_history 中的记录不一致，应用下次启动会因校验失败而拒绝启动。
-- **已应用的迁移只应追加修正，不应改写** —— 这条纪律比"迁移数量少"重要得多。
-- ============================================================

ALTER TABLE confirmation RENAME CONSTRAINT task_confirmation_pkey TO confirmation_pkey;

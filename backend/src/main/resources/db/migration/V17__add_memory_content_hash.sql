-- ============================================================
-- V17: 记忆去重加 content_hash 唯一键（审查 P2）
--
-- 背景：记忆去重此前是「Service 层精确匹配 content + 无唯一键」。
-- 并发下两个请求同时 findFirstByContent 都查不到、又都 save，就得到两条
-- 内容完全相同的记忆；模型对同一件事换个说法（同义改写）也查不到，照样堆积。
-- 加 content_hash 唯一键把「不重复」从 Service 层约定变成数据库约束——
-- 并发最多触发唯一键冲突，冲突被 Service 层捕获后转为「返回既有条目」。
--
-- 设计要点：
--   1) hash 口径与 Java 侧 JsonCodec.sha256Hex 一致（UTF-8 + SHA-256 + 小写 hex）。
--      这里用 pgcrypto 的 digest(content, 'sha256') 回填，两边输出对齐。
--   2) 先加列 → 回填 → 再设 NOT NULL + 唯一约束：三步顺序不可调换，
--      否则对已有数据直接加 NOT NULL/UNIQUE 会因空值/重复而失败。
--
-- 纪律：已应用的迁移只追加，不改写。V1-V16 零触碰。
-- ============================================================

-- digest() 来自 pgcrypto，V1 未启用，这里就地启用（幂等）
CREATE EXTENSION IF NOT EXISTS pgcrypto;

ALTER TABLE memory ADD COLUMN content_hash VARCHAR(64);

UPDATE memory SET content_hash = encode(digest(content, 'sha256'), 'hex');

ALTER TABLE memory ALTER COLUMN content_hash SET NOT NULL;

ALTER TABLE memory ADD CONSTRAINT uk_memory_content_hash UNIQUE (content_hash);

COMMENT ON COLUMN memory.content_hash IS '内容 SHA-256（小写 hex），唯一键：把「不重复」从 Service 层约定变成数据库约束';

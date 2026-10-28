-- ============================================================
-- V26: 长期记忆分层、近重复取代与召回计数（阶段 21）
--
-- 背景：V9 的 R-03 决定「记忆不做向量检索」——因为记忆是**约束性事实**，
-- 按相似度取 topN 会在「对话没提到它」时漏掉必须生效的约束。
-- 本迁移不推翻该结论，只补它留下的三个缺口（见 specs/021-memory-layering/research.md）：
--
--   1. 分层：pinned 标记「常驻」条目，它们无条件注入、优先占预算；
--      其余仍在预算内全量填充 —— 注入集合不因相似度变小。
--   2. 取代：同义改写的重复条目用 embedding 余弦判定，旧条目记 superseded_by
--      而不是物理删除 —— 用户仍能在页面上看到「被哪条取代」。
--   3. 衰减：hit_count / last_used_at 记录真实被注入的次数与时间，
--      供排序做新近度衰减；也回答「这条到底有没有用」。
--
-- 向量列的存在只为两件事（排序微调 + 近重复判定），**不用于召回**。
-- 维度必须与 V4 的 vector_store.embedding 一致（1024），否则无法与同一
-- embedding 模型产出的查询向量比较。
--
-- 纪律：已应用的迁移只追加，不改写。V1-V25 零触碰。
-- ============================================================

ALTER TABLE memory
    ADD COLUMN pinned           boolean     NOT NULL DEFAULT false,
    ADD COLUMN superseded_by    uuid        NULL REFERENCES memory (id) ON DELETE SET NULL,
    ADD COLUMN embedding        vector(1024) NULL,
    ADD COLUMN embedding_model  varchar(120) NULL,
    ADD COLUMN embedded_at      timestamptz NULL,
    ADD COLUMN hit_count        integer     NOT NULL DEFAULT 0,
    ADD COLUMN last_used_at     timestamptz NULL;

COMMENT ON COLUMN memory.pinned IS '常驻记忆：无条件注入、优先占预算。对应 R-03 说的「常驻/按需分层」';
COMMENT ON COLUMN memory.superseded_by IS '被哪条更新的记忆取代（同义改写的近重复）。非空即不再注入，但不物理删除';
COMMENT ON COLUMN memory.embedding IS '内容向量，仅用于相关度排序与近重复判定，不用于召回';
COMMENT ON COLUMN memory.embedding_model IS '产出该向量的模型名。不同模型的向量不可比，召回/比较时必须同模型';
COMMENT ON COLUMN memory.hit_count IS '被注入到对话上下文的累计次数（衰减与「有没有用」的依据）';
COMMENT ON COLUMN memory.last_used_at IS '最近一次被注入的时间；null 表示从未被注入';

-- 近重复查询按「同模型 + 未被取代」过滤后做余弦近邻，HNSW 负责近邻部分。
-- opclass 必须与查询用的算子成对：vector_cosine_ops ↔ <=>
CREATE INDEX idx_memory_embedding_hnsw ON memory USING hnsw (embedding vector_cosine_ops);

-- 注入路径固定筛「未被取代」，且 pinned 优先。部分索引比全表索引小得多。
CREATE INDEX idx_memory_active ON memory (pinned DESC, updated_at DESC) WHERE superseded_by IS NULL;

-- 回填任务按 embedded_at IS NULL 扫增量
CREATE INDEX idx_memory_pending_embedding ON memory (id) WHERE embedded_at IS NULL AND superseded_by IS NULL;

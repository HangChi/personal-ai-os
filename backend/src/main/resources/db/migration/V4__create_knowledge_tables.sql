-- ============================================================
-- V4: 阶段 3 —— 知识库文档与向量表
--
-- 设计说明见 specs/004-knowledge-rag/data-model.md。
--
-- ⚠️ 本迁移里有三处「必须与其他地方严格一致」的约定，改动前请先读注释：
--   1) vector(1024) 的维度必须与应用配置 app.knowledge.embedding-dimensions 一致，
--      启动期会读 pg_attribute 校验，不一致直接启动失败。
--   2) 列名与类型必须与 PgVectorStore 内部拼的 SQL 完全一致
--      （框架的 DDL 片段为：id / content text / metadata json / embedding vector(%d)）。
--      框架的 schema-validation 默认 false，它**不会**替我们发现写错。
--   3) 索引 opclass 必须与配置的 distance-type 成对出现：
--      COSINE_DISTANCE ↔ vector_cosine_ops。
--      二者不匹配**不会报错**，只会让索引失效、查询退化为全表扫描（症状是"慢"而非"错"）。
-- ============================================================

CREATE TABLE IF NOT EXISTS knowledge_document (
    id           UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    title        VARCHAR(300) NOT NULL,
    filename     VARCHAR(300) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    char_count   INTEGER      NOT NULL,
    chunk_count  INTEGER      NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  knowledge_document            IS '知识库文档元数据；原文不落库，只保留分块后的片段';
COMMENT ON COLUMN knowledge_document.chunk_count IS '分块数；必须等于该文档在 vector_store 中的向量条数（SC-001）';

-- ------------------------------------------------------------------
-- 向量表：由本迁移创建，**不**使用 PgVectorStore 的自动建表
-- （initialize-schema=false）。原因：结构变更必须可追溯（宪法原则 IV），
-- 且自动建表带有 remove-existing-vector-store-table 这类"误开就删表"的开关。
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(1024)
);

-- HNSW + 余弦距离：与 spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE 配套
CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON vector_store USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE  vector_store      IS '文档片段向量；metadata 中必须含 document_id（删除时按它清理）';
COMMENT ON COLUMN vector_store.metadata IS '片段元数据：document_id / document_title / chunk_index / filename';

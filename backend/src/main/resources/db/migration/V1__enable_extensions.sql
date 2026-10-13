-- ============================================================
-- V1: 阶段 0 数据库基线
--
-- 本阶段只启用扩展，不创建业务表：
--   * 阶段 0 没有任何读写业务数据的接口，提前建表属于为想象的需求建结构。
--   * 向量表 vector_store 的维度取决于阶段 1 选定的 Embedding 模型，
--     此时建表必然要改，因此推迟到阶段 3。
-- 详见 specs/001-project-skeleton/data-model.md 与偏差 D-01。
-- ============================================================

-- pgvector：阶段 3 知识库向量检索依赖
CREATE EXTENSION IF NOT EXISTS vector;

-- hstore：Spring AI 官方 pgvector 参考建表脚本会引用
CREATE EXTENSION IF NOT EXISTS hstore;

-- uuid-ossp：提供 uuid_generate_v4()，与 Spring AI 参考 DDL 的默认值保持一致
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

COMMENT ON EXTENSION vector IS 'pgvector：Personal AI OS 阶段 3 知识库向量检索使用';
COMMENT ON EXTENSION hstore IS '键值存储扩展：预留，供 Spring AI pgvector 默认结构兼容';

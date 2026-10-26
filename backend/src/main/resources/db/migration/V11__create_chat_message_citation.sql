-- ============================================================
-- V11: 对话消息引用表（阶段 19）
--
-- 背景：对话开「知识库增强」后，助手回答基于检索到的知识库片段，
-- 并在正文中以 [n] 编号引用。引用是「回答依据」的证据，必须随消息
-- 持久化——只存在于当次会话等于没有（知识页问答早有引用展示，
-- 对话历史不能缺）。
--
-- 设计要点（详见 specs/019-knowledge-chat-settings/data-model.md）：
--   1. 快照语义：title / filename / content 取「回答当时」的值，
--      **不外键 knowledge 文档**——文档可删，历史引用不应失效。
--   2. ON DELETE CASCADE：删会话 → 消息删 → 引用删，不留孤儿。
--   3. 不存 score / matchedBy：那是检索过程量，对「依据展示」无价值。
--   4. message_id 建索引：查询路径是「按消息 id 批量取」。
--
-- 纪律：已应用的迁移只追加，不改写。V1-V10 零触碰。
-- ============================================================

CREATE TABLE chat_message_citation (
    id              BIGSERIAL    PRIMARY KEY,
    message_id      BIGINT       NOT NULL REFERENCES chat_message(id) ON DELETE CASCADE,
    citation_index  INTEGER      NOT NULL,
    document_id     VARCHAR(64)  NOT NULL,
    chunk_index     INTEGER      NOT NULL,
    document_title  VARCHAR(200) NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    content         TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_citation_message_index UNIQUE (message_id, citation_index)
);

CREATE INDEX idx_citation_message ON chat_message_citation(message_id);

COMMENT ON TABLE chat_message_citation IS '助手消息的知识库引用快照：回答当时检索到的依据';
COMMENT ON COLUMN chat_message_citation.message_id IS '被引用的助手消息（级联删除）';
COMMENT ON COLUMN chat_message_citation.citation_index IS '回答正文中的引用编号 [n]，从 1 开始';
COMMENT ON COLUMN chat_message_citation.document_id IS '来源文档标识（快照，无外键：文档可删，引用不失效）';
COMMENT ON COLUMN chat_message_citation.chunk_index IS '片段在文档内的序号（0 起）';
COMMENT ON COLUMN chat_message_citation.content IS '片段内容快照，取回答当时的值';

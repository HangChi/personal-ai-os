-- ============================================================
-- V12: 阶段 22 —— 会话滚动摘要
--
-- 解决的问题：上下文窗口只带最近若干条消息，更早的对话对模型彻底不可见。
-- 用户问「我们上周聊的那个方案」时，助手只能回答「不知道」——
-- 而那条信息其实还躺在 chat_message 里。
--
-- 设计要点：
--   1) 摘要与会话同行存储（一条会话只有一份滚动摘要），不做历史版本 ——
--      摘要的唯一用途是注入提示词，保留旧版本只增加存储与「该用哪版」的歧义。
--   2) summary_upto_seq 是**覆盖边界**：摘要已涵盖 sequence_no ≤ 该值的消息。
--      用顺序号而不是时间戳，与消息排序唯一依据保持一致（V2 的约定）。
--      默认 0 表示「还没有任何摘要」，因此对既有会话是安全的空值。
--   3) context_summary 允许 NULL：与空串区分开 —— NULL 表示从未生成过。
-- ============================================================

ALTER TABLE chat_session
    ADD COLUMN IF NOT EXISTS context_summary TEXT,
    ADD COLUMN IF NOT EXISTS summary_upto_seq INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN chat_session.context_summary  IS '滚动摘要：对已滑出上下文窗口的历史对话的压缩结果，注入系统提示用';
COMMENT ON COLUMN chat_session.summary_upto_seq IS '摘要已覆盖到的消息 sequence_no（含）；0 表示尚无摘要';

-- 生成摘要时需要按会话取「边界之后」的消息，走 (conversation_id, sequence_no) 的唯一索引即可，
-- 无需额外索引。

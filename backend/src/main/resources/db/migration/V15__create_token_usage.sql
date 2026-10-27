-- ============================================================
-- V15: 每次对话的模型用量（清单第 7 项）
--
-- 背景：上下文仪表盘显示的是「本轮送进去多少」，回答「这周花了多少」
-- 需要另一份数据。用量只能在流结束时拿到，属于「过去发生过的一次记录」，
-- 无法从 chat_message 反推（字符数 ÷ 1.6 只是估算，且模型真实计费按 token）。
--
-- 设计要点：
--   1) **一行一轮**，不记录增量：按日聚合是唯一的消费方式，
--      逐 chunk 存储会让表在几周内膨胀到百万行而查询只用得上聚合值。
--   2) `source` 区分 ACTUAL / ESTIMATED：换非 OpenAI 系 provider（ollama、
--      mock）时上游不返回 usage，只能按字符估算。**不标来源就是把估算冒充真实**，
--      用户看到「本周 12 万 token」会误以为这是账单依据。
--   3) `model` 存快照而不是外键：模型名是「当时用的哪个」，换模型后
--      历史记录不该跟着变（与 V11 引用快照同理）。
--   4) `conversation_id` **不建外键**：删会话是常态操作，用量是账号级统计，
--      删了对话不该让「本周总用量」变小——那是在伪造账单。
--      （对比 V11：引用随消息级联删除，因为引用是回答的一部分。）
--   5) 不存 cost / 单价：单价随 provider 与促销变化，写进历史行里
--      会让旧记录在降价后依然显示旧价。金额在展示层按当前价算。
--
-- 纪律：已应用的迁移只追加，不改写。V1-V14 零触碰。
-- ============================================================

CREATE TABLE token_usage (
    id                BIGSERIAL    PRIMARY KEY,
    conversation_id   VARCHAR(64)  NOT NULL,
    model             VARCHAR(120),
    prompt_tokens     INTEGER      NOT NULL,
    completion_tokens INTEGER      NOT NULL,
    source            VARCHAR(16)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_token_usage_source CHECK (source IN ('ACTUAL', 'ESTIMATED')),
    CONSTRAINT ck_token_usage_non_negative CHECK (prompt_tokens >= 0 AND completion_tokens >= 0)
);

-- 查询形态是「最近 N 天，按天分组求和」，created_at 是唯一过滤条件
CREATE INDEX idx_token_usage_created_at ON token_usage(created_at);

COMMENT ON TABLE token_usage IS '每轮对话的模型用量记录（一行一轮），用于用量统计';
COMMENT ON COLUMN token_usage.conversation_id IS '所属会话（无外键：删会话不影响历史用量统计）';
COMMENT ON COLUMN token_usage.model IS '当时使用的模型名快照';
COMMENT ON COLUMN token_usage.prompt_tokens IS '送入模型的 token 数';
COMMENT ON COLUMN token_usage.completion_tokens IS '模型生成的 token 数（含思考 token）';
COMMENT ON COLUMN token_usage.source IS 'ACTUAL=上游返回的真实用量；ESTIMATED=按字符估算（provider 不支持 usage）';

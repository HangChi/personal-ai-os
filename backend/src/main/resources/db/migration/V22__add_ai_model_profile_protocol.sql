-- ============================================================
-- V22: 模型配置增加「协议」字段，用于探测该端点支持哪些模型
--
-- 背景：用户期望像 cc-switch 那样「填好端点与密钥，点一下就能看到
-- 这个端点有哪些模型，直接挑」—— 而不是手打模型名再试错。
--
-- 但「列出模型」的接口在三种协议下完全不同：
--   OpenAI     GET {base}/models           → {"data":[{"id":"gpt-4o"}, ...]}
--   Anthropic  GET {base}/v1/models        → {"data":[{"id":"claude-..."}, ...]}
--              （必须带 x-api-key 与 anthropic-version 两个头，不能用 Bearer）
--   Ollama     GET {origin}/api/tags       → {"models":[{"name":"llama3:8b"}, ...]}
--
-- 所以需要一个字段记住「这套配置属于哪种协议」，探测时才知道打哪个
-- 路径、带什么头、解析哪个字段。
--
-- 设计要点：
--   1) **协议只影响「探测模型列表」，不影响聊天**。聊天链路仍统一走
--      OpenAI 兼容协议（Spring AI 在这里只装了 openai/ollama 两个 starter）。
--      这点必须写清楚：把 protocol 理解成「聊天走的协议」会导出错误预期。
--   2) 默认值 'OPENAI'。V21 已有的配置全是 OpenAI 兼容形态（当时的唯一选择），
--      升级后不需要用户重填任何东西。
--   3) 用 CHECK 约束限定取值，而不是 ENUM 类型：ENUM 增删值要改类型，
--      CHECK 只需一条 ALTER，且与应用层的枚举保持松耦合。
--   4) 不加索引。这张表最多几十行，永远全表扫。
-- ============================================================

ALTER TABLE ai_model_profile
    ADD COLUMN protocol VARCHAR(20) NOT NULL DEFAULT 'OPENAI';

-- 已有行显式落成 OPENAI（DEFAULT 已覆盖，这里写明是为了让迁移意图自解释）
UPDATE ai_model_profile SET protocol = 'OPENAI' WHERE protocol IS NULL OR protocol = '';

ALTER TABLE ai_model_profile
    ADD CONSTRAINT ck_ai_model_profile_protocol
        CHECK (protocol IN ('OPENAI', 'ANTHROPIC', 'OLLAMA'));

COMMENT ON COLUMN ai_model_profile.protocol IS
    '协议类型，决定「获取模型列表」时调用哪个接口；聊天链路始终走 OpenAI 兼容协议';

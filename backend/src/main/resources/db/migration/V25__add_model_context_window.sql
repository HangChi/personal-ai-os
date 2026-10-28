-- ============================================================
-- V25: 每个模型自带上下文窗口长度
--
-- 背景（用户原话）：「不同模型的支持的最大上下文长度也不同，有办法做到吗」。
--
-- 在此之前，上下文窗口来自全局配置 app.ai.memory.context-window-tokens
-- （默认 131072），与「当前用哪个模型」无关。后果很具体：
--   · 用 qwen-max（32K 窗口）时，仪表盘显示「/ 128K」——分母比真实能力大 4 倍，
--     用户以为还有余量，实际请求已被端点拒绝（400 context length exceeded）；
--   · 用 qwen-long（1M 窗口）时，分母只有 128K，裁剪阈值把 87% 的能力浪费掉。
-- 一个全局值不可能同时正确，因为窗口是**模型**的属性，不是配置的属性。
--
-- 设计要点：
--   1) 窗口挂在 ai_model_profile_model 上，而不是 ai_model_profile 上。
--      一套配置下可以有 qwen-max 与 qwen-long 并存，它们的窗口差 30 倍。
--   2) 列可空。NULL = 「不知道这个模型的窗口」，此时回落到全局配置 ——
--      这是**向后兼容的关键**：已有模型行不会因为本次迁移而获得一个凭空猜的窗口。
--      宁可继续用全局默认，也不要在数据库里写一个看起来精确、实际错误的数。
--   3) 有 CHECK 下限 1024。0 或负数会让 window/预算计算除出 0 或负数，
--      表现为「仪表盘永远 100%」这种极难定位的现象；下限让脏数据进不来。
--   4) 不给上限。1M、2M 都是真实存在的量级（gemini-1.5-pro 2M），
--      写死上限只会在下次出现更大窗口时又要改一次迁移。
--
-- 纪律：已应用的迁移只追加，不改写。V1-V24 零触碰。
-- ============================================================

ALTER TABLE ai_model_profile_model
    ADD COLUMN context_window INTEGER;

-- 空值允许（含义「未知，回落全局默认」）；非空时必须 >= 1024
ALTER TABLE ai_model_profile_model
    ADD CONSTRAINT ck_ai_model_profile_model_ctx_window
        CHECK (context_window IS NULL OR context_window >= 1024);

COMMENT ON COLUMN ai_model_profile_model.context_window IS
    '该模型支持的上下文窗口（token）。NULL=未知，回落到 app.ai.memory.context-window-tokens；只在 CHAT 类上有意义';

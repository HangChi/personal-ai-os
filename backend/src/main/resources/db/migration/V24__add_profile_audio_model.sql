-- ============================================================
-- V24: 语音模型进清单，并给每套配置一个默认语音模型
--
-- 背景：设置页的模型下拉早就把语音模型（asr / tts / audio / speech 等）识别
-- 成 AUDIO 类，但**只用于把它们从对话/嵌入下拉里剔除** —— 既没有配置位，
-- 也没有任何调用路径。用户配了语音模型之后问「现在能用吗」，
-- 答案是「不能，因为你连配它的地方都没有」。
--
-- 本迁移解决「能不能配」。真正的调用链路由
-- AudioTranscriptionService（/api/v1/chat/transcribe）提供。
--
-- 设计要点：
--   1) kind 的 CHECK 加一个 'AUDIO'。**这是必须改的**：
--      V23 写的是 CHECK (kind IN ('CHAT', 'EMBEDDING'))，往子表插 AUDIO
--      会直接被库层拒绝 —— 表现为一个 500 的约束冲突，
--      而不是「功能没做」这种能一眼看懂的状态。
--   2) audio_model 单值列与 chat_model / embedding_model 同构：
--      存「这套配置默认用哪个语音模型」（= 清单第一项）。
--      不另建一张表：语音模型在配置里的地位与对话模型完全一致，
--      都是「同一把密钥、同一个端点下的一个模型名」。
--   3) **不搬迁历史数据**：V23 之前不存在语音模型清单，
--      这里没有可搬的东西。保持 NULL = 没有语音模型 = 语音输入回落到
--      浏览器自带的 Web Speech API。
--
-- 纪律：已应用的迁移只追加，不改写。V1-V23 零触碰。
-- ============================================================

-- ------------------------------------------------------------
-- 1) 放开 kind 的取值域
-- ------------------------------------------------------------
ALTER TABLE ai_model_profile_model
    DROP CONSTRAINT ck_ai_model_profile_model_kind;

ALTER TABLE ai_model_profile_model
    ADD CONSTRAINT ck_ai_model_profile_model_kind
        CHECK (kind IN ('CHAT', 'EMBEDDING', 'AUDIO'));

COMMENT ON COLUMN ai_model_profile_model.kind IS
    'CHAT=对话类（含视觉），EMBEDDING=嵌入类，AUDIO=语音类（ASR/TTS）；分栏展示与「换嵌入要重建向量」的提示依赖它';

-- ------------------------------------------------------------
-- 2) 默认语音模型（= AUDIO 清单第一项）
--
-- 与 chat_model 一样只是「默认用哪个」的快照，完整清单在子表里。
-- 允许为 NULL：绝大多数用户不需要自定义语音模型，
-- 此时语音输入走浏览器自带的识别能力，不消耗任何 API 额度。
-- ------------------------------------------------------------
ALTER TABLE ai_model_profile
    ADD COLUMN audio_model VARCHAR(120);

COMMENT ON COLUMN ai_model_profile.audio_model IS
    '默认语音模型名（NULL=不配置，语音输入回落到浏览器自带识别）。V24 起完整清单存于 ai_model_profile_model 的 AUDIO 行';

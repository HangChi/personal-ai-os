-- ============================================================
-- V23: 一套 API 配置可保存多个模型；每个会话可单独选模型
--
-- 背景（用户原话）：「一个 api 可以配置多个模型，而不是只能一个」。
--
-- V21 的 ai_model_profile 把模型名存成两个单值列
-- （chat_model / embedding_model）。但一个端点通常提供几十上百个模型
-- —— 实测 DashScope 一次探测就返回 183 个（160 对话 / 3 视觉 / 2 嵌入 / 18 音频）。
-- 「这套配置只属于某一个模型」与实际使用方式不符：同一把密钥、同一个端点，
-- 用户会在 qwen-max（推理）、qwen-plus（日常省钱）、qwen-vl-max（看图）之间换着用。
--
-- 因此把模型名从「配置的列」改成「配置的从属清单」，并让会话可以各选各的。
--
-- 设计要点：
--   1) **不改写 V1-V22**。单值列 chat_model / embedding_model 原样保留，
--      继续作为「旧代码读到的默认模型」与回滚时的快照。新逻辑只读子表，
--      子表为空时才回落到旧列（见下文的搬迁与 ModelProfileService 的注释）。
--   2) 子表用 kind 区分模型用途。目前只落 CHAT / EMBEDDING 两个值：
--      视觉（VISION）等类别在探测结果里能识别，但还没接入运行期，
--      提前写进约束只会让将来加类别时又得改 CHECK。
--   3) **不存 is_default 标志**。默认对话模型仍由 ai_model_profile.chat_model
--      这一列表达（它就是「这套配置默认用哪个」），避免同一事实存在两处、
--      在两处不一致时无从判断谁对。子表只回答「这套配置认得哪些模型」。
--   4) 会话的 chat_model 单独一列，NULL = 沿用配置默认。
--      不加外键到子表：子表行被删掉不该连带把历史会话改成 NULL ——
--      那条会话当时确实是用那个模型聊的，这是历史事实，不是配置。
--      （同理不给它 CHECK，用户可能手填一个清单外的模型名。）
--   5) 嵌入模型允许多个，但**换嵌入模型会导致向量维度变化**，
--      已有向量全部失效。本迁移只负责把清单存下来；重建向量是另一条
--      独立流程（需要用户显式触发），不在这里偷偷做。
--
-- 纪律：已应用的迁移只追加，不改写。V1-V22 零触碰。
-- ============================================================

-- ------------------------------------------------------------
-- 1) 模型清单子表
-- ------------------------------------------------------------
CREATE TABLE ai_model_profile_model (
    id          BIGSERIAL    PRIMARY KEY,

    -- 属于哪一套配置。配置被删则清单一并删除（清单离开配置没有意义）
    profile_id  UUID         NOT NULL REFERENCES ai_model_profile (id) ON DELETE CASCADE,

    -- 用途：CHAT = 对话/视觉等聊天类，EMBEDDING = 嵌入类。
    -- 分开的理由：界面上分两栏展示，且「换嵌入模型要重建向量」这条警告
    -- 只对 EMBEDDING 那一栏成立，混在一起没法给出准确的提示。
    kind        VARCHAR(20)  NOT NULL,

    -- 模型名。与 ai_model_profile.chat_model 同宽（120）
    model_name  VARCHAR(120) NOT NULL,

    -- 清单内的展示顺序 = 用户挑模型的顺序（常用的放前面）
    sort_order  INTEGER      NOT NULL DEFAULT 0,

    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_ai_model_profile_model_kind CHECK (kind IN ('CHAT', 'EMBEDDING')),
    CONSTRAINT ck_ai_model_profile_model_name_not_blank CHECK (length(btrim(model_name)) > 0)
);

-- 同一套配置下，同一类里不允许重复的模型名。
-- 用唯一约束而不是应用层判重：整体替换清单时是「先删后插」，
-- 若前端提交了重复项，应用层很容易在批量插入时漏掉其中一条的判定。
CREATE UNIQUE INDEX uq_ai_model_profile_model_name
    ON ai_model_profile_model (profile_id, kind, model_name);

-- 列表读取的唯一形态：按配置取全部，按 kind, sort_order 排序
CREATE INDEX idx_ai_model_profile_model_order
    ON ai_model_profile_model (profile_id, kind, sort_order);

COMMENT ON TABLE  ai_model_profile_model IS '一套配置下保存的模型名清单；由用户在设置页维护，可在会话中选用';
COMMENT ON COLUMN ai_model_profile_model.profile_id IS '所属配置；配置删除时级联删除';
COMMENT ON COLUMN ai_model_profile_model.kind IS 'CHAT=对话类（含视觉），EMBEDDING=嵌入类；分栏展示与「换嵌入要重建向量」的提示依赖它';
COMMENT ON COLUMN ai_model_profile_model.model_name IS '模型名，如 qwen-max；不校验是否真实存在（端点可能动态增减模型）';
COMMENT ON COLUMN ai_model_profile_model.sort_order IS '清单内展示顺序；常用模型放前面';

-- ------------------------------------------------------------
-- 2) 把已有单值列搬进清单
--
-- 不搬的后果是「升级后用户已有的配置在界面上显示为『还没有模型』」，
-- 而它其实能正常聊天 —— 一个需要用户重新手打一遍的无谓损失。
--
-- 只在子表为空时搬（NOT EXISTS），使本迁移可在一个已被手工改过的库上
-- 安全执行，也不会把用户后来手工维护的清单覆盖成单条。
-- ------------------------------------------------------------
INSERT INTO ai_model_profile_model (profile_id, kind, model_name, sort_order)
SELECT p.id, 'CHAT', btrim(p.chat_model), 0
FROM ai_model_profile p
WHERE p.chat_model IS NOT NULL
  AND length(btrim(p.chat_model)) > 0
  AND NOT EXISTS (SELECT 1 FROM ai_model_profile_model m WHERE m.profile_id = p.id);

INSERT INTO ai_model_profile_model (profile_id, kind, model_name, sort_order)
SELECT p.id, 'EMBEDDING', btrim(p.embedding_model), 0
FROM ai_model_profile p
WHERE p.embedding_model IS NOT NULL
  AND length(btrim(p.embedding_model)) > 0
  AND NOT EXISTS (
      SELECT 1 FROM ai_model_profile_model m
      WHERE m.profile_id = p.id AND m.kind = 'EMBEDDING');

COMMENT ON COLUMN ai_model_profile.chat_model IS
    '默认对话模型名（NULL=回落环境变量）。V23 起完整清单存于 ai_model_profile_model，本列仍表示「默认用哪个」';
COMMENT ON COLUMN ai_model_profile.embedding_model IS
    '默认嵌入模型名（NULL=回落环境变量）。V23 起完整清单存于 ai_model_profile_model；本列仅为兼容与回滚保留';

-- ------------------------------------------------------------
-- 3) 会话级模型选择
--
-- NULL = 沿用当前生效配置的默认模型。这样：
--   · 绝大多数会话不必写任何值，行为与改造前完全一致；
--   · 用户换了一套生效配置时，没选过模型的会话自动跟着走 —— 这正是期望；
--   · 一旦用户在某会话里显式选过，那个选择就固定下来，不受换配置影响。
-- 注意这个「选过」是**历史选择**，模型名不在清单里也不清空：
-- 那条会话当时确实用它聊过，抹掉会让历史记录失真。
-- ------------------------------------------------------------
ALTER TABLE chat_session
    ADD COLUMN chat_model VARCHAR(120);

COMMENT ON COLUMN chat_session.chat_model IS
    '该会话选定的对话模型名；NULL 表示沿用生效配置的默认模型。历史事实，不随配置变动而清空';

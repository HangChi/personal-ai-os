-- ============================================================
-- V21: 大模型「多套配置」表（替代 V20 的单行表）
--
-- 背景：V20 只能存**一套**配置。用户同时有 DashScope、官方 OpenAI、
-- 本地 Ollama、几家中转站是很常见的，而每次换用都得把端点/密钥/模型
-- 全部重填一遍，还容易漏填导致「明明切了却在用旧端点」。
-- 参照 cc-switch 的 provider 管理：多套配置并列成卡片，点一下就切换。
--
-- 设计要点：
--   1) **多行表 + 至多一行 active**。用「部分唯一索引」把这条不变量写进
--      数据库，而不是靠应用层自觉：
--          CREATE UNIQUE INDEX ... ON ai_model_profile (active) WHERE active
--      索引只覆盖 active = TRUE 的行，因此「至多一行 TRUE」在库层强制成立。
--      应用层怎么写错都不可能插进第二个生效配置。
--      ⚠️ 不能用 CHECK 约束表达 —— CHECK 看不到其他行。
--   2) **未设置 = NULL，不是空串**。与 V20 一脉相承：NULL 表示「这一项
--      用环境变量/内置默认」，空串表示「用户显式清空了」。切换配置时
--      某一项可以留空，让它继续吃 .env 的值，这叫「只覆盖我要改的那几项」。
--   3) `api_key` 明文存 —— 与 V20 相同的取舍，理由见 V20 头部注释。
--      接口层继续掩码（只回末 4 位），这一层不变。
--   4) `preset_id` 记录「这条配置是从哪个预设建出来的」，用于卡片上显示
--      供应商标识，也用于「预设升级」时提示用户。允许为 NULL（自定义配置）。
--      不加外键：预设库在代码里（前端常量），不是数据库表 —— 预设增删
--      不该要求迁移。
--   5) `sort_order` 手动排序，与 cc-switch 的拖拽排序对应。相同值时按
--      created_at 兜底，保证列表顺序稳定（否则每次刷新顺序可能变）。
--   6) **保留 V20 的 ai_model_config 表不动**。理由：
--      - 迁移只追加，不改写已有表的语义；
--      - 数据搬迁走 INSERT ... SELECT，搬完两表并存一段时间，
--        出问题可以立刻回滚代码而不必回滚数据库；
--      - V20 表在当前阶段已无代码引用，作为历史快照留着零成本。
--      （若要清理，另起一个迁移 DROP，不与本次功能混在一起。）
--
-- 纪律：已应用的迁移只追加，不改写。V1-V20 零触碰。
-- ============================================================

CREATE TABLE ai_model_profile (
    id              UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),

    -- 展示名称。用户自己起的名字，如「DashScope 主力」「OpenAI 官方」
    name            VARCHAR(60)  NOT NULL,

    -- 供应商预设标识（前端的 PRESET_ID，如 'dashscope' / 'openai' / 'ollama'）；
    -- NULL 表示「自定义配置」，不从任何预设来
    preset_id       VARCHAR(60),

    base_url        VARCHAR(500),
    api_key         VARCHAR(500),
    chat_model      VARCHAR(120),
    temperature     DOUBLE PRECISION,
    embedding_model VARCHAR(120),

    -- 是否当前生效。TRUE 的行全表至多一行（由下方部分唯一索引保证）
    active          BOOLEAN      NOT NULL DEFAULT FALSE,

    -- 手动排序位；相同值时按 created_at 升序兜底
    sort_order      INTEGER      NOT NULL DEFAULT 0,

    -- 保存后自增，用于回读确认与审计；初值 0，首次保存变 1
    revision        INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_ai_model_profile_name_not_blank CHECK (length(btrim(name)) > 0),
    -- 温度范围与 OpenAI 协议一致。NULL 表示「未覆盖」，不参与校验。
    CONSTRAINT ck_ai_model_profile_temperature
        CHECK (temperature IS NULL OR (temperature >= 0 AND temperature <= 2)),
    CONSTRAINT ck_ai_model_profile_revision_non_negative CHECK (revision >= 0)
);

-- ------------------------------------------------------------
-- 「至多一行 active」的库层强制
--
-- 部分索引（partial index）只包含 active = TRUE 的行。由于索引唯一，
-- 库层会拒绝第二个 TRUE。这是本表最重要的一条约束：
-- 少了它，「当前生效」就变成「谁先读谁生效」的不确定状态。
--
-- 命名里带 active 而不是 idx_profile_singleton_active：
-- 排错时从报错信息 "duplicate key value violates unique constraint
-- uq_ai_model_profile_single_active" 能直接看出是哪条不变量被违反。
-- ------------------------------------------------------------
CREATE UNIQUE INDEX uq_ai_model_profile_single_active
    ON ai_model_profile (active)
    WHERE active;

-- 列表查询按 sort_order, created_at 排序；这是唯一的列表形态
CREATE INDEX idx_ai_model_profile_order ON ai_model_profile (sort_order, created_at);

COMMENT ON TABLE  ai_model_profile IS '大模型 API 多套配置；active = TRUE 的行全表至多一行（部分唯一索引强制）';
COMMENT ON COLUMN ai_model_profile.name IS '用户起的展示名，如「DashScope 主力」';
COMMENT ON COLUMN ai_model_profile.preset_id IS '供应商预设标识（前端 PRESET_ID）；NULL 表示自定义配置';
COMMENT ON COLUMN ai_model_profile.base_url IS 'OpenAI 兼容端点根地址；NULL 表示回落到环境变量 OPENAI_BASE_URL';
COMMENT ON COLUMN ai_model_profile.api_key IS 'API 密钥明文；接口层永不返回明文，只返回末 4 位掩码';
COMMENT ON COLUMN ai_model_profile.chat_model IS '对话模型名；NULL 表示回落到环境变量 OPENAI_CHAT_MODEL';
COMMENT ON COLUMN ai_model_profile.temperature IS '采样温度 0~2；NULL 表示沿用服务端默认';
COMMENT ON COLUMN ai_model_profile.embedding_model IS '嵌入模型名；**改动只在下次启动生效**（向量表维度固定 1024）';
COMMENT ON COLUMN ai_model_profile.active IS '是否当前生效；全表至多一行为 TRUE（见 uq_ai_model_profile_single_active）';
COMMENT ON COLUMN ai_model_profile.sort_order IS '手动排序位；相同值时按 created_at 兜底保证顺序稳定';
COMMENT ON COLUMN ai_model_profile.revision IS '保存后自增；初值 0，首次保存后为 1';

-- ------------------------------------------------------------
-- 数据搬迁：把 V20 那一行变成第一个 profile
--
-- 为什么必须搬：用户可能已经在设置页存过配置。不搬就是「升级后配置消失、
-- 聊天突然用回默认端点」，且没有任何提示 —— 这是最伤人的一类升级。
--
-- 两个守卫：
--   · 只在 ai_model_config 确实有行时插入（INSERT ... SELECT 天然满足）；
--   · 只在 ai_model_profile 还没有「生效」行时才标 active = TRUE，
--     避免在一个已被手工改过的库上插入第二个 active 而触发唯一索引报错。
--     （全新库上两边都是空，结果就是一行 active，正是期望状态。）
--
-- 名称固定为「默认配置」而不是取端点域名：搬迁是自动行为，猜一个
-- 「看起来更聪明」的名字反而让用户不确定它是哪来的。用户随时可改名。
-- ------------------------------------------------------------
INSERT INTO ai_model_profile (name, base_url, api_key, chat_model, temperature,
                              embedding_model, active, sort_order, revision,
                              created_at, updated_at)
SELECT '默认配置',
       c.base_url,
       c.api_key,
       c.chat_model,
       c.temperature,
       c.embedding_model,
       NOT EXISTS (SELECT 1 FROM ai_model_profile WHERE active),
       0,
       c.revision,
       c.updated_at,
       c.updated_at
FROM ai_model_config c
WHERE c.id = 1;

-- ============================================================
-- V20: 大模型 API 配置（可在设置页热切换）
--
-- 背景：此前模型配置完全由环境变量在**启动期**决定（AI_CHAT_PROVIDER /
-- OPENAI_BASE_URL / OPENAI_API_KEY / OPENAI_CHAT_MODEL），设置页对这些
-- 既读不到也写不了。改一次模型要编辑 .env 再重启进程，而前端没有任何
-- 入口说明「现在到底连的是哪个服务」。
--
-- 设计要点：
--   1) **单行表**。本地单用户应用只有一个生效配置；用 id=1 的固定主键 +
--      CHECK 约束把「只有一行」写进数据库，而不是靠应用层自觉。
--      多环境（开发/生产）不靠多行表达，各自连各自的库。
--   2) `api_key` 明文存。这是**有意识的取舍**而非疏忽：本机自托管、库不对外、
--      单用户，加密的密钥本身还得放在 .env 里，收益是把攻击面从「读库」
--      挪到「读 .env」，并没有变小。真正防的是**截屏与日志泄露**，
--      那由接口层做掩码返回解决（GET 永不返回明文）。
--   3) 存的全是「用户意图值」，不存派生的默认值。某一列为 NULL 表示
--      「用户没设过，用环境变量/内置默认」，而不是「用户设成了空」。
--      这样后端换了 .env 默认值，没被覆盖的项能跟着变。
--   4) **不存 embedding 维度**：维度由向量表 DDL 固定为 1024，换嵌入模型
--      如果维度不同会直接写入失败，这是启动期守卫的职责，不是配置表能表达的。
--   5) `revision` 单调递增，用于「保存后回读确认」与将来审计；不使用时间戳，
--      因为同一毫秒内两次保存要能区分先后。
--
-- 纪律：已应用的迁移只追加，不改写。V1-V19 零触碰。
-- ============================================================

CREATE TABLE ai_model_config (
    id              SMALLINT     PRIMARY KEY,
    base_url        VARCHAR(500),
    api_key         VARCHAR(500),
    chat_model      VARCHAR(120),
    temperature     DOUBLE PRECISION,
    embedding_model VARCHAR(120),
    revision        INTEGER      NOT NULL DEFAULT 1,
    updated_at      TIMESTAMPTZ  NOT NULL,

    -- 单行约束：本地单用户只有一个生效配置。写成 CHECK 而不是只靠应用层，
    -- 是为了让「误插第二行」在数据库层直接失败，而不是变成「谁生效看运气」。
    CONSTRAINT ck_ai_model_config_singleton CHECK (id = 1),

    -- 温度范围与 OpenAI 协议一致。NULL 表示「未覆盖」，不参与校验。
    CONSTRAINT ck_ai_model_config_temperature
        CHECK (temperature IS NULL OR (temperature >= 0 AND temperature <= 2)),

    CONSTRAINT ck_ai_model_config_revision_positive CHECK (revision > 0)
);

COMMENT ON TABLE ai_model_config IS '大模型 API 配置（单行，id 恒为 1）；未设置的列回落到环境变量与内置默认';
COMMENT ON COLUMN ai_model_config.base_url IS 'OpenAI 兼容端点根地址，如 https://dashscope.aliyuncs.com/compatible-mode/v1';
COMMENT ON COLUMN ai_model_config.api_key IS 'API 密钥明文；接口层永不返回明文，只返回掩码';
COMMENT ON COLUMN ai_model_config.chat_model IS '对话模型名，如 qwen3.8-flash';
COMMENT ON COLUMN ai_model_config.temperature IS '采样温度 0~2；NULL 表示沿用环境变量配置';
COMMENT ON COLUMN ai_model_config.embedding_model IS '嵌入模型名；**改动只在下次启动生效**（向量表维度固定 1024）';
COMMENT ON COLUMN ai_model_config.revision IS '保存后自增，用于回读确认与审计';
COMMENT ON COLUMN ai_model_config.updated_at IS '最后一次保存时间';

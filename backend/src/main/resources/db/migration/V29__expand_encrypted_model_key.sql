-- 500 字符明文 + IV/tag + Base64 后可能超过原列长度；只扩容，不修改现有值。
ALTER TABLE ai_model_config ALTER COLUMN api_key TYPE VARCHAR(3000);
ALTER TABLE ai_model_profile ALTER COLUMN api_key TYPE VARCHAR(3000);

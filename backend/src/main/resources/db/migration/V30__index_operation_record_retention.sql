-- 可选审计保留策略按创建时间清理，避免随历史增长退化为全表扫描。
CREATE INDEX idx_operation_record_created_at ON operation_record(created_at);

-- 短事务租约；模型网络调用期间不持有数据库连接，最终写入使用 token fencing。
CREATE TABLE runtime_lease (
    resource VARCHAR(160) NOT NULL,
    slot INTEGER NOT NULL,
    owner UUID,
    lease_until TIMESTAMPTZ NOT NULL DEFAULT 'epoch',
    PRIMARY KEY(resource, slot)
);
CREATE TABLE runtime_rate_bucket (
    name VARCHAR(100) PRIMARY KEY,
    tokens DOUBLE PRECISION NOT NULL,
    refilled_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

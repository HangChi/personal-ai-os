-- 订阅按浏览器保存；每个订阅/提醒独立送达，不改变提醒的已读状态。
CREATE TABLE web_push_subscription (
    id UUID PRIMARY KEY,
    endpoint VARCHAR(2048) NOT NULL UNIQUE,
    p256dh VARCHAR(128) NOT NULL,
    auth VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE web_push_delivery (
    subscription_id UUID NOT NULL REFERENCES web_push_subscription(id) ON DELETE CASCADE,
    reminder_id UUID NOT NULL REFERENCES reminder(id) ON DELETE CASCADE,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (subscription_id, reminder_id)
);
CREATE INDEX idx_web_push_delivery_pending ON web_push_delivery(next_attempt_at)
    WHERE state IN ('PENDING', 'SENDING');

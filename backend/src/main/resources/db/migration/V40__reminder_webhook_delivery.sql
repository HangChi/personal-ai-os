CREATE TABLE reminder_webhook_target (
    target VARCHAR(64) PRIMARY KEY,
    enabled_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE reminder_webhook_delivery (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    target VARCHAR(64) NOT NULL REFERENCES reminder_webhook_target(target),
    reminder_id UUID NOT NULL REFERENCES reminder(id) ON DELETE CASCADE,
    state VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','SENDING','SENT','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(target,reminder_id)
);
CREATE INDEX reminder_webhook_ready ON reminder_webhook_delivery(target,next_attempt_at)
WHERE state IN ('PENDING','SENDING');

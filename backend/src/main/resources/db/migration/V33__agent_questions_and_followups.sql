ALTER TABLE chat_run DROP CONSTRAINT chat_run_status_check;
ALTER TABLE chat_run ADD CONSTRAINT chat_run_status_check CHECK
    (status IN ('RUNNING','COMPLETED','WAITING_CONFIRMATION','WAITING_USER','INTERRUPTED','STOPPED'));
CREATE TABLE agent_question (
 id varchar(64) PRIMARY KEY,
 run_id varchar(64) NOT NULL REFERENCES chat_run(id) ON DELETE CASCADE,
 question text NOT NULL,
 options text NOT NULL,
 digest varchar(64) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ANSWERED')),
 answer text,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(run_id,digest)
);
CREATE UNIQUE INDEX uk_agent_question_pending ON agent_question(run_id) WHERE status='PENDING';
CREATE TABLE agent_followup (
 id varchar(64) PRIMARY KEY,
 seq bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
 run_id varchar(64) NOT NULL REFERENCES chat_run(id) ON DELETE CASCADE,
 conversation_id varchar(64) NOT NULL REFERENCES chat_session(conversation_id) ON DELETE CASCADE,
 mode varchar(8) NOT NULL CHECK (mode IN ('QUEUE','STEER')),
 request text NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPLIED','CANCELLED')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_followup_pending ON agent_followup(conversation_id,seq) WHERE status='PENDING';

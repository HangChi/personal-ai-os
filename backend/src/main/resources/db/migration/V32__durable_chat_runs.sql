-- 页面连接与执行解耦；事件序号用于断线后增量追赶。
CREATE TABLE chat_run (
 id varchar(64) PRIMARY KEY,
 conversation_id varchar(64) NOT NULL REFERENCES chat_session(conversation_id) ON DELETE CASCADE,
 request text NOT NULL,
 status varchar(24) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','WAITING_CONFIRMATION','INTERRUPTED','STOPPED')),
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_chat_run_running ON chat_run(conversation_id) WHERE status = 'RUNNING';
CREATE INDEX idx_chat_run_session ON chat_run(conversation_id,created_at DESC);
CREATE TABLE chat_run_event (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 run_id varchar(64) NOT NULL REFERENCES chat_run(id) ON DELETE CASCADE,
 name varchar(32) NOT NULL,
 payload text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_run_event_cursor ON chat_run_event(run_id,id);

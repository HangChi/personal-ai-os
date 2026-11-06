-- 只读报告独立领取；持久Run只以已完成回执写入，通用Agent不能接管。
ALTER TABLE chat_session ADD COLUMN read_only_report boolean NOT NULL DEFAULT false;
ALTER TABLE chat_run ADD COLUMN origin varchar(24) NOT NULL DEFAULT 'CHAT' CHECK(origin IN ('CHAT','READONLY_WORKFLOW'));
ALTER TABLE chat_run ADD CONSTRAINT readonly_run_completed CHECK(origin<>'READONLY_WORKFLOW' OR status='COMPLETED');
CREATE FUNCTION protect_report_session() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='chat_session' THEN
   IF OLD.read_only_report AND NOT NEW.read_only_report THEN RAISE EXCEPTION 'workflow:只读报告会话不可转为Agent会话'; END IF;
 ELSE
   IF EXISTS(SELECT 1 FROM chat_session WHERE conversation_id=NEW.conversation_id AND read_only_report) THEN
     IF TG_TABLE_NAME='chat_message' THEN RAISE EXCEPTION 'workflow:报告会话只读，请新建对话';
     ELSIF NEW.origin<>'READONLY_WORKFLOW' THEN RAISE EXCEPTION 'workflow:报告会话只读，请新建对话'; END IF;
   END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER report_session_protect BEFORE UPDATE OF read_only_report ON chat_session FOR EACH ROW EXECUTE FUNCTION protect_report_session();
CREATE TRIGGER report_message_protect BEFORE INSERT OR UPDATE ON chat_message FOR EACH ROW EXECUTE FUNCTION protect_report_session();
CREATE TRIGGER report_run_protect BEFORE INSERT OR UPDATE ON chat_run FOR EACH ROW EXECUTE FUNCTION protect_report_session();

CREATE TABLE workflow (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 name varchar(100) NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 100),
 kind varchar(24) NOT NULL CHECK(kind IN ('MORNING_BRIEF','WEEKLY_REVIEW','KNOWLEDGE_REVIEW')),
 timezone varchar(100) NOT NULL,
 local_time time NOT NULL,
 weekday integer NOT NULL DEFAULT 1 CHECK(weekday BETWEEN 1 AND 7),
 enabled boolean NOT NULL DEFAULT false,
 max_runs_per_day integer NOT NULL DEFAULT 3 CHECK(max_runs_per_day BETWEEN 1 AND 24),
 max_items integer NOT NULL DEFAULT 30 CHECK(max_items BETWEEN 1 AND 100),
 max_output_chars integer NOT NULL DEFAULT 10000 CHECK(max_output_chars BETWEEN 1000 AND 20000),
 next_run_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE workflow_execution (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),workflow_id uuid NOT NULL REFERENCES workflow(id),
 scheduled_for timestamptz NOT NULL,source varchar(12) NOT NULL CHECK(source IN ('MANUAL','SCHEDULED')),
 definition jsonb NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 3),
 claim_token uuid,lease_until timestamptz,available_at timestamptz NOT NULL DEFAULT now(),
 report text,error varchar(300),model_calls integer NOT NULL DEFAULT 0 CHECK(model_calls=0),
 run_id varchar(64) REFERENCES chat_run(id) ON DELETE SET NULL,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(workflow_id,scheduled_for)
);
CREATE INDEX workflow_execution_ready ON workflow_execution(available_at) WHERE status IN ('PENDING','RUNNING');
CREATE TABLE workflow_attempt (
 execution_id uuid NOT NULL REFERENCES workflow_execution(id) ON DELETE CASCADE,
 attempt integer NOT NULL CHECK(attempt BETWEEN 1 AND 3),started_at timestamptz NOT NULL DEFAULT now(),
 finished_at timestamptz,status varchar(16) NOT NULL DEFAULT 'RUNNING',error varchar(300),
 PRIMARY KEY(execution_id,attempt)
);
CREATE FUNCTION workflow_next_at(p_zone text,p_time time,p_weekday integer,p_kind text,p_after timestamptz) RETURNS timestamptz LANGUAGE plpgsql STABLE AS $$
DECLARE day date;candidate timestamptz;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=p_zone) THEN RAISE EXCEPTION 'workflow:无效IANA时区'; END IF;
 day:=(p_after AT TIME ZONE p_zone)::date;
 FOR i IN 0..7 LOOP
  candidate:=(day+p_time) AT TIME ZONE p_zone;
  -- 缺失时刻按时区规则向后移，重叠时刻采用较晚偏移；两端共用数据库语义。
  IF candidate>p_after AND (p_kind<>'WEEKLY_REVIEW' OR extract(isodow FROM day)=p_weekday) THEN RETURN candidate; END IF;
  day:=day+1;
 END LOOP;
 RAISE EXCEPTION 'workflow:无法计算下次时间';
END $$;
CREATE FUNCTION prepare_workflow() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.next_run_at:=workflow_next_at(NEW.timezone,NEW.local_time,NEW.weekday,NEW.kind,clock_timestamp());NEW.updated_at:=now();RETURN NEW;
END $$;
CREATE TRIGGER workflow_prepare BEFORE INSERT OR UPDATE OF kind,timezone,local_time,weekday,enabled ON workflow FOR EACH ROW EXECUTE FUNCTION prepare_workflow();
CREATE FUNCTION enqueue_workflow(p_id uuid,p_manual boolean) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE w workflow%ROWTYPE;existing uuid;period timestamptz;id uuid;
BEGIN
 SELECT * INTO w FROM workflow WHERE workflow.id=p_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'workflow:计划不存在'; END IF;
 IF NOT p_manual AND (NOT w.enabled OR w.next_run_at>now()) THEN RETURN NULL; END IF;
 IF p_manual THEN
   SELECT e.id INTO existing FROM workflow_execution e WHERE workflow_id=p_id AND status IN ('PENDING','RUNNING') ORDER BY created_at LIMIT 1;
   IF existing IS NOT NULL THEN RETURN existing; END IF;
   period:=clock_timestamp();
 ELSE period:=w.next_run_at;
 END IF;
 INSERT INTO workflow_execution(workflow_id,scheduled_for,source,definition) VALUES(p_id,period,CASE WHEN p_manual THEN 'MANUAL' ELSE 'SCHEDULED' END,to_jsonb(w)) ON CONFLICT(workflow_id,scheduled_for) DO NOTHING RETURNING workflow_execution.id INTO id;
 IF NOT p_manual THEN UPDATE workflow SET next_run_at=workflow_next_at(w.timezone,w.local_time,w.weekday,w.kind,clock_timestamp()) WHERE workflow.id=p_id; END IF;
 RETURN id;
END $$;
CREATE FUNCTION dispatch_workflows() RETURNS void LANGUAGE plpgsql AS $$
DECLARE id uuid;
BEGIN
 FOR id IN SELECT workflow.id FROM workflow WHERE enabled AND next_run_at<=now() ORDER BY next_run_at FOR UPDATE SKIP LOCKED LIMIT 10 LOOP PERFORM enqueue_workflow(id,false); END LOOP;
END $$;
CREATE FUNCTION claim_workflow() RETURNS SETOF workflow_execution LANGUAGE plpgsql AS $$
DECLARE e workflow_execution%ROWTYPE;w workflow%ROWTYPE;n integer;day_start timestamptz;day_end timestamptz;
BEGIN
 UPDATE workflow_attempt a SET status='FAILED',error='执行租约超时',finished_at=now() FROM workflow_execution x WHERE x.id=a.execution_id AND x.status='RUNNING' AND x.lease_until<now() AND a.status='RUNNING';
 UPDATE workflow_execution SET status='FAILED',error='执行重试次数已达上限',lease_until=NULL WHERE status='RUNNING' AND lease_until<now() AND attempts>=3;
 SELECT * INTO e FROM workflow_execution WHERE attempts<3 AND ((status='PENDING' AND available_at<=now()) OR (status='RUNNING' AND lease_until<now())) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1;
 IF NOT FOUND THEN RETURN; END IF;
 SELECT * INTO w FROM workflow WHERE id=e.workflow_id FOR UPDATE;
 IF e.source='SCHEDULED' AND NOT w.enabled THEN UPDATE workflow_execution SET status='FAILED',error='计划已禁用，待执行自动期次已取消' WHERE id=e.id;RETURN;END IF;
 day_start:=((now() AT TIME ZONE w.timezone)::date)::timestamp AT TIME ZONE w.timezone;
 day_end:=(((now() AT TIME ZONE w.timezone)::date)+1)::timestamp AT TIME ZONE w.timezone;
 SELECT count(*) INTO n FROM workflow_attempt a JOIN workflow_execution x ON x.id=a.execution_id WHERE x.workflow_id=w.id AND a.started_at>=day_start AND a.started_at<day_end;
 IF n>=w.max_runs_per_day THEN UPDATE workflow_execution SET status='FAILED',error='今日执行次数已达上限，可次日重试',lease_until=NULL WHERE id=e.id;RETURN;END IF;
 UPDATE workflow_execution SET status='RUNNING',attempts=attempts+1,claim_token=gen_random_uuid(),lease_until=now()+interval '60 seconds',error=NULL,updated_at=now() WHERE id=e.id RETURNING * INTO e;
 INSERT INTO workflow_attempt(execution_id,attempt) VALUES(e.id,e.attempts);
 RETURN NEXT e;
END $$;
CREATE FUNCTION finish_workflow(p_id uuid,p_token uuid,p_report text,p_error text) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE e workflow_execution%ROWTYPE;v_session_id text;v_run_id text;
BEGIN
 SELECT * INTO e FROM workflow_execution WHERE id=p_id AND status='RUNNING' AND claim_token=p_token AND lease_until>=clock_timestamp() FOR UPDATE;
 IF NOT FOUND THEN RETURN false; END IF;
 IF p_error IS NOT NULL THEN
  UPDATE workflow_attempt SET status='FAILED',error=left(p_error,300),finished_at=now() WHERE execution_id=e.id AND attempt=e.attempts;
  UPDATE workflow_execution SET status=CASE WHEN attempts<3 THEN 'PENDING' ELSE 'FAILED' END,error=left(p_error,300),available_at=now()+interval '30 seconds',lease_until=NULL,updated_at=now() WHERE id=e.id;RETURN true;
 END IF;
 IF p_report IS NULL OR length(p_report)>(e.definition->>'max_output_chars')::int THEN RAISE EXCEPTION 'workflow:报告输出超出限制'; END IF;
 v_session_id:=gen_random_uuid()::text;v_run_id:=e.id::text;
 INSERT INTO chat_session(conversation_id,title) VALUES(v_session_id,left('只读报告 · '||(e.definition->>'name'),200));
 INSERT INTO chat_message(conversation_id,message_type,content,sequence_no) VALUES(v_session_id,'ASSISTANT',p_report,1);
 UPDATE chat_session SET read_only_report=true WHERE conversation_id=v_session_id;
 INSERT INTO chat_run(id,conversation_id,request,status,origin) VALUES(v_run_id,v_session_id,'{}','COMPLETED','READONLY_WORKFLOW');
 INSERT INTO chat_run_event(run_id,name,payload) VALUES(v_run_id,'done','{"readOnly":true,"modelCalls":0}');
 UPDATE workflow_attempt SET status='SUCCEEDED',finished_at=now() WHERE execution_id=e.id AND attempt=e.attempts;
 UPDATE workflow_execution SET status='SUCCEEDED',report=p_report,run_id=v_run_id,lease_until=NULL,error=NULL,updated_at=now() WHERE id=e.id;
 RETURN true;
END $$;

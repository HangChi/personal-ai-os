-- 两端共用纯校验规则；不导入业务，不建立向量，不执行系统命令。
CREATE TABLE runtime_heartbeat (
 instance_id uuid PRIMARY KEY,backend varchar(8) NOT NULL CHECK(backend IN ('JAVA','GO')),
 role varchar(12) NOT NULL CHECK(role IN ('combined','api','index','review','delivery')),
 started_at timestamptz NOT NULL DEFAULT now(),last_seen_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE maintenance_check (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),kind varchar(20) NOT NULL CHECK(kind IN ('BUSINESS_JSON','EXTERNAL_DRILL')),
 status varchar(20) NOT NULL CHECK(status IN ('VALID','INVALID','UNVERIFIED')),
 digest varchar(64),result jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE FUNCTION maintenance_field(v jsonb,t text,required boolean,max_length integer) RETURNS boolean LANGUAGE plpgsql STABLE AS $$
DECLARE s text;
BEGIN
 IF v IS NULL OR v='null'::jsonb THEN RETURN NOT required; END IF;
 s:=v#>>'{}';
 IF t IN ('string','text','uuid','instant','date','timezone','conversation','status','priority','previous','recurrence','role','lifecycle') THEN
  IF jsonb_typeof(v)<>'string' OR (required AND t<>'string' AND length(btrim(s))=0) OR (max_length>0 AND length(s)>max_length) THEN RETURN false; END IF;
 END IF;
 CASE t
 WHEN 'text' THEN RETURN true;
 WHEN 'string' THEN RETURN true;
 WHEN 'uuid' THEN RETURN s ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
 WHEN 'conversation' THEN RETURN s ~ '^[A-Za-z0-9-]{1,64}$';
 WHEN 'instant' THEN
  IF s !~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$' THEN RETURN false; END IF;
  PERFORM s::timestamptz;RETURN true;
 WHEN 'date' THEN IF s !~ '^\d{4}-\d{2}-\d{2}$' THEN RETURN false; END IF;PERFORM s::date;RETURN true;
 WHEN 'timezone' THEN RETURN EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=s);
 WHEN 'bool' THEN RETURN jsonb_typeof(v)='boolean';
 WHEN 'uint' THEN RETURN jsonb_typeof(v)='number' AND s ~ '^\d+$' AND s::numeric<=2147483647;
 WHEN 'ulong' THEN RETURN jsonb_typeof(v)='number' AND s ~ '^\d+$' AND s::numeric<=9223372036854775807;
 WHEN 'array' THEN RETURN jsonb_typeof(v)='array';
 WHEN 'object' THEN RETURN jsonb_typeof(v)='object';
 WHEN 'status' THEN RETURN s IN ('TODO','DOING','DONE');
 WHEN 'priority' THEN RETURN s IN ('LOW','MEDIUM','HIGH');
 WHEN 'previous' THEN RETURN s IN ('TODO','DOING');
 WHEN 'recurrence' THEN RETURN s IN ('NONE','DAILY','WEEKDAYS','WEEKLY','MONTHLY');
 WHEN 'role' THEN RETURN s IN ('USER','ASSISTANT');
 WHEN 'lifecycle' THEN RETURN s IN ('ACTIVE','SUPERSEDED');
 ELSE RETURN false;
 END CASE;
EXCEPTION WHEN OTHERS THEN RETURN false;
END $$;
-- 规则由 type:required:max_length 表示，未知字段允许以兼容旧版/扩展导出。
CREATE FUNCTION maintenance_record(v jsonb,rules jsonb,path text) RETURNS text[] LANGUAGE plpgsql STABLE AS $$
DECLARE issues text[]:=ARRAY[]::text[];f record;parts text[];
BEGIN
 IF jsonb_typeof(v) IS DISTINCT FROM 'object' THEN RETURN ARRAY[path||'：应为对象']; END IF;
 FOR f IN SELECT * FROM jsonb_each_text(rules) LOOP
  parts:=string_to_array(f.value,':');
  IF NOT maintenance_field(v->f.key,parts[1],parts[2]='1',parts[3]::integer) THEN issues:=array_append(issues,path||'.'||f.key||'：类型、必填值或范围无效'); END IF;
 END LOOP;
 RETURN issues;
END $$;
CREATE FUNCTION maintenance_validate(p jsonb) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$
DECLARE issues text[]:=ARRAY[]::text[];counts jsonb:='{}';k text;v jsonb;m jsonb;s jsonb;parent jsonb;
 projects jsonb;tasks jsonb;memories jsonb;row_data record;i integer;j integer;n integer;expected integer;messages integer:=0;chunks integer:=0;
 zones text[]:=ARRAY(SELECT name FROM pg_timezone_names);
 path text;id text;ref text;cursor_id text;seen text[];start_at timestamptz;end_at timestamptz;
BEGIN
 IF jsonb_typeof(p) IS DISTINCT FROM 'object' OR p->>'formatVersion' IS DISTINCT FROM '1' OR jsonb_typeof(p->'formatVersion') IS DISTINCT FROM 'string' THEN
  RETURN jsonb_build_object('status','INVALID','issues',jsonb_build_array('仅支持 formatVersion 为字符串 1 的业务 JSON 对象'),'counts',counts,'truncated',false);
 END IF;
 FOREACH k IN ARRAY ARRAY['sessions','tasks','calendarEvents','memories','knowledgeDocuments','projects'] LOOP
  IF NOT p ? k AND k='projects' THEN CONTINUE; END IF;
  IF jsonb_typeof(p->k) IS DISTINCT FROM 'array' THEN issues:=array_append(issues,k||'：应为数组');CONTINUE; END IF;
  n:=jsonb_array_length(p->k);counts:=counts||jsonb_build_object(k,n);
  IF n>10000 THEN issues:=array_append(issues,k||'：超过单类10000条校验上限'); END IF;
  IF p ? 'counts' AND (NOT maintenance_field(p->'counts'->k,'uint',true,0) OR p->'counts'->>k IS DISTINCT FROM n::text) THEN issues:=array_append(issues,'counts.'||k||'：与实际条数不一致');END IF;
 END LOOP;
 IF cardinality(issues)>0 THEN RETURN jsonb_build_object('status','INVALID','issues',to_jsonb(issues),'counts',counts,'truncated',false);END IF;
 -- 一次聚合建立引用索引，避免逐条扫描整份备份。
 SELECT coalesce(jsonb_object_agg(lower(e->>'id'),e),'{}') INTO projects FROM jsonb_array_elements(coalesce(p->'projects','[]')) e WHERE e->>'id' IS NOT NULL;
 SELECT coalesce(jsonb_object_agg(lower(e->>'id'),e),'{}') INTO tasks FROM jsonb_array_elements(p->'tasks') e WHERE e->>'id' IS NOT NULL;
 SELECT coalesce(jsonb_object_agg(lower(e->>'id'),e),'{}') INTO memories FROM jsonb_array_elements(p->'memories') e WHERE e->>'id' IS NOT NULL;
 FOREACH k IN ARRAY ARRAY['projects','tasks','calendarEvents','memories','knowledgeDocuments','sessions'] LOOP
  SELECT count(*)-count(DISTINCT CASE WHEN k='sessions' THEN e->'session'->>'conversationId' WHEN k='knowledgeDocuments' THEN lower(e->'document'->>'id') ELSE lower(e->>'id') END) INTO n FROM jsonb_array_elements(coalesce(p->k,'[]')) e;
  IF n>0 THEN issues:=array_append(issues,k||'：存在重复或缺失标识'); END IF;
  i:=0;
  FOR v IN SELECT * FROM jsonb_array_elements(coalesce(p->k,'[]')) LOOP
   i:=i+1;path:=k||'['||i||']';
   IF k='projects' THEN
    issues:=issues||maintenance_record(v,'{"id":"uuid:1:36","name":"text:1:100","description":"text:0:2000","archived":"bool:0:0","createdAt":"instant:0:0","updatedAt":"instant:0:0"}',path);
   ELSIF k='tasks' THEN
    issues:=issues||maintenance_record(v,'{"id":"uuid:1:36","title":"text:1:200","description":"text:0:10000","status":"status:1:0","priority":"priority:1:0","tags":"array:0:0","dueAt":"instant:0:0","completedAt":"instant:0:0","createdAt":"instant:0:0","updatedAt":"instant:0:0","previousStatus":"previous:0:0","projectId":"uuid:0:36","parentTaskId":"uuid:0:36","recurrenceType":"recurrence:0:0","recurrenceTimezone":"text:0:100","recurrenceAnchorDay":"uint:0:0","recurrenceGenerated":"bool:0:0"}',path);
    IF v->>'projectId' IS NOT NULL AND NOT projects ? lower(v->>'projectId') THEN issues:=array_append(issues,path||'：项目引用不存在'); END IF;
    IF v->>'parentTaskId' IS NOT NULL THEN
     parent:=tasks->lower(v->>'parentTaskId');
     IF parent IS NULL OR lower(v->>'parentTaskId')=lower(v->>'id') OR parent->>'parentTaskId' IS NOT NULL OR coalesce(parent->>'recurrenceType','NONE')<>'NONE' THEN issues:=array_append(issues,path||'：父任务必须为存在的非周期根任务，不能循环');
     ELSIF lower(parent->>'projectId') IS DISTINCT FROM lower(v->>'projectId') OR (parent->>'status'='DONE' AND v->>'status'<>'DONE') THEN issues:=array_append(issues,path||'：父子任务项目或完成状态不一致'); END IF;
    END IF;
    IF coalesce(v->>'recurrenceType','NONE')<>'NONE' AND (v->>'dueAt' IS NULL OR v->>'parentTaskId' IS NOT NULL) THEN issues:=array_append(issues,path||'：周期任务需要截止时间且不能是子任务'); END IF;
    IF v->>'recurrenceTimezone' IS NOT NULL AND NOT v->>'recurrenceTimezone'=ANY(zones) THEN issues:=array_append(issues,path||'：无效周期时区'); END IF;
    IF v->>'recurrenceAnchorDay' IS NOT NULL AND maintenance_field(v->'recurrenceAnchorDay','uint',true,0) THEN
     IF (v->>'recurrenceAnchorDay')::numeric NOT BETWEEN 1 AND 31 THEN issues:=array_append(issues,path||'：周期锚定日需在1至31之间'); END IF;
    END IF;
    IF v->>'status'<>'DONE' AND (v->>'completedAt' IS NOT NULL OR v->>'previousStatus' IS NOT NULL) THEN issues:=array_append(issues,path||'：未完成任务不能携带完成时间或完成前状态'); END IF;
    IF jsonb_typeof(v->'tags')='array' THEN
     IF jsonb_array_length(v->'tags')>100 THEN issues:=array_append(issues,path||'：标签超过100个');
     ELSE FOR s IN SELECT * FROM jsonb_array_elements(v->'tags') LOOP IF NOT maintenance_field(s,'text',true,20) THEN issues:=array_append(issues,path||'：标签应为1至20字符的文本');EXIT;END IF; END LOOP;END IF;
    END IF;
   ELSIF k='calendarEvents' THEN
    issues:=issues||maintenance_record(v,'{"id":"uuid:1:36","title":"text:1:200","description":"text:0:10000","location":"text:0:200","allDay":"bool:0:0","startsAt":"instant:1:0","endsAt":"instant:1:0","recurrenceType":"recurrence:0:0","recurrenceUntil":"instant:0:0","reminderLeadMinutes":"uint:0:0","createdAt":"instant:0:0"}',path);
    IF maintenance_field(v->'startsAt','instant',true,0) AND maintenance_field(v->'endsAt','instant',true,0) THEN
     start_at:=(v->>'startsAt')::timestamptz;end_at:=(v->>'endsAt')::timestamptz;
     IF end_at<start_at THEN issues:=array_append(issues,path||'：结束时间早于开始时间'); END IF;
     IF v->>'recurrenceUntil' IS NOT NULL AND maintenance_field(v->'recurrenceUntil','instant',true,0) AND (v->>'recurrenceUntil')::timestamptz<start_at THEN issues:=array_append(issues,path||'：重复截止时间早于开始时间'); END IF;
    END IF;
   ELSIF k='memories' THEN
    issues:=issues||maintenance_record(v,'{"id":"uuid:1:36","content":"text:1:2000","source":"text:0:40","pinned":"bool:0:0","supersededBy":"uuid:0:36","hitCount":"uint:0:0","lastUsedAt":"instant:0:0","createdAt":"instant:0:0","updatedAt":"instant:0:0"}',path);
    cursor_id:=lower(v->>'id');seen:=ARRAY[cursor_id];ref:=lower(v->>'supersededBy');j:=0;
    WHILE ref IS NOT NULL LOOP
     j:=j+1;
     IF ref=ANY(seen) OR j>64 THEN issues:=array_append(issues,path||'：记忆取代链循环或超过64层校验上限');EXIT;END IF;
     IF NOT memories ? ref THEN issues:=array_append(issues,path||'：记忆取代引用不存在');EXIT;END IF;
     seen:=array_append(seen,ref);ref:=lower(memories->ref->>'supersededBy');
    END LOOP;
   ELSIF k='sessions' THEN
    issues:=issues||maintenance_record(v,'{"session":"object:1:0","messages":"array:1:0"}',path);
    issues:=issues||maintenance_record(v->'session','{"conversationId":"conversation:1:64","title":"text:0:200","createdAt":"instant:1:0","updatedAt":"instant:0:0","readOnlyReport":"bool:0:0"}',path||'.session');
    IF jsonb_typeof(v->'messages')='array' THEN
     messages:=messages+jsonb_array_length(v->'messages');
     IF messages>50000 THEN issues:=array_append(issues,'消息总数超过50000条校验上限');EXIT; END IF;
     SELECT count(*)-count(DISTINCT e->>'sequenceNo') INTO n FROM jsonb_array_elements(v->'messages') e;
     IF n>0 THEN issues:=array_append(issues,path||'：消息序号重复或缺失'); END IF;
     j:=0;FOR m IN SELECT * FROM jsonb_array_elements(v->'messages') LOOP
      j:=j+1;issues:=issues||maintenance_record(m,'{"role":"role:1:0","content":"string:1:0","sequenceNo":"uint:1:0","createdAt":"instant:0:0"}',path||'.messages['||j||']');
      IF m->>'sequenceNo'='0' THEN issues:=array_append(issues,path||'：消息序号需为正整数'); END IF;
      IF cardinality(issues)>=100 THEN EXIT; END IF;
     END LOOP;
    END IF;
   ELSIF k='knowledgeDocuments' THEN
    issues:=issues||maintenance_record(v,'{"document":"object:1:0","chunks":"array:1:0","source":"object:0:0"}',path);
    issues:=issues||maintenance_record(v->'document','{"id":"uuid:1:36","title":"text:1:300","filename":"text:1:300","contentType":"text:0:100","sizeBytes":"ulong:0:0","charCount":"uint:0:0","chunkCount":"uint:1:0","createdAt":"instant:0:0"}',path||'.document');
    IF jsonb_typeof(v->'chunks')='array' THEN
     n:=jsonb_array_length(v->'chunks');chunks:=chunks+n;
     IF chunks>50000 THEN issues:=array_append(issues,'分块总数超过50000条校验上限');EXIT; END IF;
     IF n=0 OR v->'document'->>'chunkCount' IS DISTINCT FROM n::text THEN issues:=array_append(issues,path||'：分块为空或与chunkCount不一致'); END IF;
     FOR m IN SELECT * FROM jsonb_array_elements(v->'chunks') LOOP IF NOT maintenance_field(m,'text',true,0) THEN issues:=array_append(issues,path||'：分块应为非空文本');EXIT;END IF; END LOOP;
    END IF;
    IF v->>'source' IS NOT NULL THEN
     s:=v->'source';issues:=issues||maintenance_record(s,'{"spaceId":"uuid:0:36","spaceName":"text:1:120","version":"text:0:120","scope":"text:0:500","authority":"text:0:300","lifecycle":"lifecycle:1:0","effectiveFrom":"date:0:0","effectiveUntil":"date:0:0","revision":"ulong:0:0"}',path||'.source');
     IF maintenance_field(s->'effectiveFrom','date',true,0) AND maintenance_field(s->'effectiveUntil','date',true,0) AND s->>'effectiveUntil'<s->>'effectiveFrom' THEN issues:=array_append(issues,path||'：资料有效日期倒置'); END IF;
    END IF;
   END IF;
   IF cardinality(issues)>=100 THEN EXIT; END IF;
  END LOOP;
  IF cardinality(issues)>=100 THEN EXIT; END IF;
 END LOOP;
 SELECT count(*)-count(DISTINCT e->>'content') INTO n FROM jsonb_array_elements(p->'memories') e;
 IF n>0 THEN issues:=array_append(issues,'memories：存在重复正文，恢复时会触发唯一约束'); END IF;
 counts:=counts||jsonb_build_object('messages',messages,'chunks',chunks);
 RETURN jsonb_build_object('status',CASE WHEN cardinality(issues)=0 THEN 'VALID' ELSE 'INVALID' END,'issues',to_jsonb(issues[1:100]),'counts',counts,'truncated',cardinality(issues)>=100);
END $$;
CREATE FUNCTION maintenance_save(p_kind text,p_digest text,p_result jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE v maintenance_check%ROWTYPE;
BEGIN
 PERFORM pg_advisory_xact_lock(350047);
 INSERT INTO maintenance_check(kind,status,digest,result) VALUES(p_kind,p_result->>'status',p_digest,p_result) RETURNING * INTO v;
 DELETE FROM maintenance_check WHERE id IN (SELECT id FROM maintenance_check ORDER BY created_at DESC,id DESC OFFSET 200);
 RETURN p_result||jsonb_build_object('id',v.id,'kind',v.kind,'digest',v.digest,'createdAt',v.created_at);
END $$;
CREATE FUNCTION maintenance_snapshot() RETURNS jsonb LANGUAGE sql STABLE AS $$
SELECT jsonb_build_object(
 'generatedAt',now(),
 'heartbeats',jsonb_build_object('total',(SELECT count(*) FROM runtime_heartbeat),'items',coalesce((SELECT jsonb_agg(to_jsonb(v)) FROM (SELECT instance_id AS "instanceId",backend,role,started_at AS "startedAt",last_seen_at AS "lastSeenAt",CASE WHEN last_seen_at<now()-interval '90 seconds' THEN 'STALE' ELSE 'ACTIVE' END AS status FROM runtime_heartbeat ORDER BY last_seen_at DESC LIMIT 50)v),'[]'::jsonb)),
 'failedIndexJobs',jsonb_build_object('total',(SELECT count(*) FROM knowledge_index_job WHERE status='FAILED'),'items',coalesce((SELECT jsonb_agg(to_jsonb(v)) FROM (SELECT id,status,attempts,updated_at AS "updatedAt" FROM knowledge_index_job WHERE status='FAILED' ORDER BY updated_at DESC LIMIT 20)v),'[]'::jsonb)),
 'failedDeliveries',jsonb_build_object('total',(SELECT count(*) FROM web_push_delivery WHERE state='FAILED')+(SELECT count(*) FROM reminder_webhook_delivery WHERE state='FAILED'),'items',coalesce((SELECT jsonb_agg(to_jsonb(v)) FROM (SELECT * FROM (SELECT subscription_id::text||'/'||reminder_id::text AS id,'WEB_PUSH' AS kind,state AS status,attempts,next_attempt_at AS "updatedAt" FROM web_push_delivery WHERE state='FAILED' UNION ALL SELECT id::text,'WEBHOOK',state,attempts,next_attempt_at FROM reminder_webhook_delivery WHERE state='FAILED')q ORDER BY "updatedAt" DESC LIMIT 20)v),'[]'::jsonb)),
 'failedWorkflows',jsonb_build_object('total',(SELECT count(*) FROM workflow_execution WHERE status='FAILED'),'items',coalesce((SELECT jsonb_agg(to_jsonb(v)) FROM (SELECT e.id,w.name,e.workflow_id AS "workflowId",e.attempts,e.status,e.error,e.updated_at AS "updatedAt" FROM workflow_execution e JOIN workflow w ON w.id=e.workflow_id WHERE e.status='FAILED' ORDER BY e.updated_at DESC LIMIT 20)v),'[]'::jsonb)),
 'unfinishedRuns',jsonb_build_object('total',(SELECT count(*) FROM chat_run r WHERE origin='CHAT' AND status<>'COMPLETED' AND NOT EXISTS(SELECT 1 FROM chat_run newer WHERE newer.conversation_id=r.conversation_id AND (newer.created_at,newer.id)>(r.created_at,r.id))),'items',coalesce((SELECT jsonb_agg(to_jsonb(v)) FROM (SELECT r.id,r.conversation_id AS "conversationId",s.title,r.status,r.updated_at AS "updatedAt" FROM chat_run r JOIN chat_session s USING(conversation_id) WHERE origin='CHAT' AND status<>'COMPLETED' AND NOT EXISTS(SELECT 1 FROM chat_run newer WHERE newer.conversation_id=r.conversation_id AND (newer.created_at,newer.id)>(r.created_at,r.id)) ORDER BY r.updated_at DESC LIMIT 20)v),'[]'::jsonb)),
 'checks',jsonb_build_object('total',(SELECT count(*) FROM maintenance_check),'items',coalesce((SELECT jsonb_agg(v.result||jsonb_build_object('id',v.id,'kind',v.kind,'digest',v.digest,'createdAt',v.created_at)) FROM (SELECT * FROM maintenance_check ORDER BY created_at DESC LIMIT 20)v),'[]'::jsonb))
);
$$;

CREATE FUNCTION maintenance_parse(p_text text) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN RETURN p_text::jsonb;
EXCEPTION WHEN invalid_text_representation OR untranslatable_character OR character_not_in_repertoire OR program_limit_exceeded THEN RETURN NULL;
END $$;
CREATE FUNCTION maintenance_verify_text(p_text text) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$
DECLARE p jsonb:=maintenance_parse(p_text);
BEGIN
 IF p IS NULL THEN RETURN '{"status":"INVALID","counts":{},"issues":["文件不是可处理的有效UTF-8 JSON"],"truncated":false}'::jsonb;END IF;
 RETURN maintenance_validate(p);
END $$;
CREATE FUNCTION maintenance_drill_text(p_text text) RETURNS jsonb LANGUAGE plpgsql STABLE AS $$
DECLARE p jsonb:=maintenance_parse(p_text);issues text[];
BEGIN
 issues:=maintenance_record(p,'{"tool":"text:1:80","summary":"text:1:2000","completedAt":"instant:1:0","outcome":"text:1:6","artifactDigest":"text:0:64"}','report');
 IF p->>'outcome' NOT IN ('PASSED','FAILED') THEN issues:=array_append(issues,'report.outcome：应为PASSED或FAILED');END IF;
 IF p->>'artifactDigest' IS NOT NULL AND (p->>'artifactDigest') !~ '^[0-9a-fA-F]{64}$' THEN issues:=array_append(issues,'report.artifactDigest：应为SHA-256摘要'); END IF;
 IF cardinality(issues)>0 THEN RETURN jsonb_build_object('status','INVALID','issues',to_jsonb(issues)); END IF;
 RETURN jsonb_build_object('status','UNVERIFIED','source','EXTERNAL','independentVerification',false,'tool',p->'tool','summary',p->'summary','completedAt',p->'completedAt','outcome',p->'outcome','artifactDigest',p->'artifactDigest');
END $$;

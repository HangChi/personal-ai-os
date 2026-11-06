-- 网页来源与正文版本独立于治理 revision；成功索引与当前版本在同一事务推进。
CREATE TABLE knowledge_capture (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 canonical_url varchar(2048) NOT NULL UNIQUE,
 document_id uuid REFERENCES knowledge_document(id) ON DELETE SET NULL,
 current_version integer NOT NULL DEFAULT 0,
 current_hash varchar(64),
 pending_job_id uuid,
 last_checked_at timestamptz NOT NULL DEFAULT now(),
 last_error varchar(300),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE knowledge_capture_version (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 capture_id uuid NOT NULL REFERENCES knowledge_capture(id) ON DELETE CASCADE,
 version integer NOT NULL CHECK(version>0),
 job_id uuid NOT NULL UNIQUE,
 title varchar(200) NOT NULL,
 content text NOT NULL CHECK(octet_length(content) BETWEEN 1 AND 1048576),
 content_hash varchar(64) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SUCCEEDED','FAILED')),
 captured_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(capture_id,version)
);
CREATE FUNCTION queue_knowledge_capture(p_url text,p_title text,p_content text,p_hash text) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE c knowledge_capture%ROWTYPE; j uuid; v integer;
BEGIN
 IF length(p_url)>2048 OR length(p_title)>200 OR octet_length(p_content) NOT BETWEEN 1 AND 1048576 OR p_hash !~ '^[0-9a-f]{64}$' THEN RAISE EXCEPTION 'capture:资料超出限制'; END IF;
 -- 与既有上传队列共用容量锁，且不在此事务中执行任何网络或模型工作。
 PERFORM pg_advisory_xact_lock(181801,1);
 INSERT INTO knowledge_capture(canonical_url) VALUES(p_url) ON CONFLICT(canonical_url) DO NOTHING;
 SELECT * INTO c FROM knowledge_capture WHERE canonical_url=p_url FOR UPDATE;
 IF c.pending_job_id IS NOT NULL THEN RETURN c.id; END IF;
 UPDATE knowledge_capture SET last_checked_at=now(),last_error=NULL WHERE id=c.id;
 IF c.document_id IS NOT NULL AND c.current_hash=p_hash THEN RETURN c.id; END IF;
 IF (SELECT count(*) FROM knowledge_index_job WHERE status IN ('PENDING','RUNNING'))>=20 THEN RAISE EXCEPTION 'capture:后台任务队列已满，请稍后再试'; END IF;
 j:=gen_random_uuid();
 INSERT INTO knowledge_index_job(id,document_id,replacing,filename,title,payload,digest)
 VALUES(j,coalesce(c.document_id,j),c.document_id IS NOT NULL,'web-capture.txt',p_title,convert_to(p_content,'UTF8'),p_hash);
 SELECT coalesce(max(version),0)+1 INTO v FROM knowledge_capture_version WHERE capture_id=c.id;
 INSERT INTO knowledge_capture_version(capture_id,version,job_id,title,content,content_hash) VALUES(c.id,v,j,p_title,p_content,p_hash);
 UPDATE knowledge_capture SET pending_job_id=j WHERE id=c.id;
 RETURN c.id;
END $$;
CREATE FUNCTION complete_knowledge_capture() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE v knowledge_capture_version%ROWTYPE;
BEGIN
 IF NEW.status NOT IN ('SUCCEEDED','FAILED') OR NEW.status=OLD.status THEN RETURN NEW; END IF;
 SELECT * INTO v FROM knowledge_capture_version WHERE job_id=NEW.id;
 IF NOT FOUND THEN RETURN NEW; END IF;
 UPDATE knowledge_capture_version SET status=NEW.status WHERE id=v.id;
 IF NEW.status='SUCCEEDED' THEN
   UPDATE knowledge_capture SET document_id=NEW.document_id,current_version=v.version,current_hash=v.content_hash,pending_job_id=NULL,last_error=NULL WHERE id=v.capture_id AND pending_job_id=NEW.id;
 ELSE
   UPDATE knowledge_capture SET pending_job_id=NULL,last_error=left(coalesce(NEW.error,'索引失败，请刷新来源重试'),300) WHERE id=v.capture_id AND pending_job_id=NEW.id;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER knowledge_capture_job_complete AFTER UPDATE OF status ON knowledge_index_job FOR EACH ROW EXECUTE FUNCTION complete_knowledge_capture();

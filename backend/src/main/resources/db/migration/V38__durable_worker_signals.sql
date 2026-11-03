CREATE SEQUENCE work_signal_revision;
CREATE TABLE work_signal_outbox (
    topic VARCHAR(30) NOT NULL,
    item_id UUID NOT NULL,
    revision BIGINT NOT NULL DEFAULT nextval('work_signal_revision'),
    next_publish TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(topic,item_id)
);
ALTER TABLE knowledge_review_job ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE knowledge_review_job ADD COLUMN last_error VARCHAR(300);

-- 唤醒记录与业务状态同事务，发布确认不删除业务任务。
CREATE FUNCTION worker_signal_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE topic_name TEXT; item UUID; ready_at TIMESTAMPTZ;
BEGIN
    IF TG_TABLE_NAME='knowledge_index_job' THEN
        topic_name='index'; item=CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
        IF TG_OP<>'DELETE' AND NEW.status IN ('PENDING','RUNNING') THEN
            ready_at=CASE WHEN NEW.status='RUNNING' THEN NEW.lease_until ELSE NEW.available_at END;
        END IF;
    ELSIF TG_TABLE_NAME='knowledge_review_job' THEN
        topic_name='review'; item=CASE WHEN TG_OP='DELETE' THEN OLD.document_id ELSE NEW.document_id END;
        IF TG_OP<>'DELETE' THEN ready_at=NEW.next_attempt_at; END IF;
    ELSE
        topic_name='search'; item=CASE WHEN TG_OP='DELETE' THEN OLD.chunk_id ELSE NEW.chunk_id END;
        IF TG_OP<>'DELETE' THEN ready_at=now(); END IF;
    END IF;
    IF ready_at IS NULL THEN
        DELETE FROM work_signal_outbox WHERE topic=topic_name AND item_id=item;
    ELSE
        INSERT INTO work_signal_outbox(topic,item_id,next_publish) VALUES(topic_name,item,ready_at)
        ON CONFLICT(topic,item_id) DO UPDATE SET revision=nextval('work_signal_revision'),next_publish=EXCLUDED.next_publish;
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER knowledge_index_signal AFTER INSERT OR UPDATE OF status,available_at,lease_until OR DELETE
ON knowledge_index_job FOR EACH ROW EXECUTE FUNCTION worker_signal_changed();
CREATE TRIGGER knowledge_review_signal AFTER INSERT OR UPDATE OR DELETE
ON knowledge_review_job FOR EACH ROW EXECUTE FUNCTION worker_signal_changed();
CREATE TRIGGER knowledge_search_signal AFTER INSERT OR UPDATE OF revision OR DELETE
ON knowledge_search_outbox FOR EACH ROW EXECUTE FUNCTION worker_signal_changed();
INSERT INTO work_signal_outbox(topic,item_id,next_publish)
SELECT 'index',id,coalesce(lease_until,available_at) FROM knowledge_index_job WHERE status IN ('PENDING','RUNNING');
INSERT INTO work_signal_outbox(topic,item_id,next_publish) SELECT 'review',document_id,next_attempt_at FROM knowledge_review_job;
INSERT INTO work_signal_outbox(topic,item_id) SELECT 'search',chunk_id FROM knowledge_search_outbox;

CREATE FUNCTION reset_review_attempts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.revision<>OLD.revision THEN NEW.attempts=0; NEW.last_error=NULL; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER review_attempt_reset BEFORE UPDATE OF revision ON knowledge_review_job
FOR EACH ROW EXECUTE FUNCTION reset_review_attempts();

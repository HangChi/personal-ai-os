-- PostgreSQL 是唯一事实源；按 chunk 合并通知，序列号用于外部版本防止旧 worker 覆盖新内容。
CREATE SEQUENCE knowledge_search_revision;
CREATE TABLE knowledge_search_outbox (
    chunk_id UUID PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT nextval('knowledge_search_revision'),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE FUNCTION enqueue_knowledge_chunk() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        INSERT INTO knowledge_search_outbox(chunk_id) VALUES(OLD.id)
        ON CONFLICT(chunk_id) DO UPDATE SET revision=nextval('knowledge_search_revision'), changed_at=now();
    END IF;
    IF TG_OP <> 'DELETE' THEN
        INSERT INTO knowledge_search_outbox(chunk_id) VALUES(NEW.id)
        ON CONFLICT(chunk_id) DO UPDATE SET revision=nextval('knowledge_search_revision'), changed_at=now();
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER knowledge_chunk_projection AFTER INSERT OR UPDATE OR DELETE ON vector_store
FOR EACH ROW EXECUTE FUNCTION enqueue_knowledge_chunk();
CREATE FUNCTION enqueue_knowledge_document_chunks() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO knowledge_search_outbox(chunk_id)
    SELECT id FROM vector_store WHERE metadata->>'document_id'=OLD.id::text
    ON CONFLICT(chunk_id) DO UPDATE SET revision=nextval('knowledge_search_revision'),changed_at=now();
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER knowledge_document_projection BEFORE UPDATE OR DELETE ON knowledge_document
FOR EACH ROW EXECUTE FUNCTION enqueue_knowledge_document_chunks();
INSERT INTO knowledge_search_outbox(chunk_id) SELECT id FROM vector_store;

CREATE TABLE knowledge_space(id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),name VARCHAR(120) NOT NULL UNIQUE);
INSERT INTO knowledge_space VALUES('00000000-0000-0000-0000-000000000001','默认知识库');
CREATE TABLE knowledge_source (
    document_id UUID PRIMARY KEY REFERENCES knowledge_document(id) ON DELETE CASCADE,
    space_id UUID NOT NULL REFERENCES knowledge_space(id),
    version VARCHAR(120) NOT NULL DEFAULT '',
    scope VARCHAR(500) NOT NULL DEFAULT '',
    effective_from DATE,
    effective_until DATE,
    authority VARCHAR(300) NOT NULL DEFAULT '',
    lifecycle VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK(lifecycle IN ('ACTIVE','SUPERSEDED')),
    revision BIGINT NOT NULL DEFAULT 1,
    review_state VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    CHECK(effective_until IS NULL OR effective_from IS NULL OR effective_until>=effective_from)
);
CREATE TABLE knowledge_review_job (
    document_id UUID PRIMARY KEY REFERENCES knowledge_document(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    chunk_cursor INTEGER NOT NULL DEFAULT 0,
    lease_token UUID,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE knowledge_conflict (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    fingerprint VARCHAR(64) NOT NULL UNIQUE,
    left_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    right_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    left_revision BIGINT NOT NULL, right_revision BIGINT NOT NULL,
    left_chunk INTEGER NOT NULL, right_chunk INTEGER NOT NULL,
    left_quote TEXT NOT NULL,right_quote TEXT NOT NULL,reason TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED','STALE')),
    resolution VARCHAR(20),winner_id UUID, resolution_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),resolved_at TIMESTAMPTZ
);
CREATE INDEX knowledge_conflict_open ON knowledge_conflict(left_id,right_id) WHERE status='OPEN';
CREATE TABLE knowledge_conflict_audit (
    id BIGSERIAL PRIMARY KEY, conflict_id UUID NOT NULL,decision JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE FUNCTION knowledge_source_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.revision=CASE WHEN TG_OP='UPDATE' THEN OLD.revision+1 ELSE NEW.revision END;
    NEW.review_state='PENDING';
    INSERT INTO knowledge_review_job(document_id,revision) VALUES(NEW.document_id,NEW.revision)
    ON CONFLICT(document_id) DO UPDATE SET revision=EXCLUDED.revision,chunk_cursor=0,next_attempt_at=now();
    UPDATE knowledge_conflict SET status='STALE' WHERE left_id=NEW.document_id OR right_id=NEW.document_id;
    RETURN NEW;
END $$;
CREATE TRIGGER knowledge_source_revision BEFORE INSERT OR UPDATE OF space_id,version,scope,effective_from,effective_until,authority,lifecycle,revision
ON knowledge_source FOR EACH ROW EXECUTE FUNCTION knowledge_source_changed();
CREATE FUNCTION initialize_knowledge_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO knowledge_source(document_id,space_id) VALUES(NEW.id,'00000000-0000-0000-0000-000000000001'); RETURN NEW;
END $$;
CREATE TRIGGER knowledge_document_source AFTER INSERT ON knowledge_document FOR EACH ROW EXECUTE FUNCTION initialize_knowledge_source();
INSERT INTO knowledge_source(document_id,space_id) SELECT id,'00000000-0000-0000-0000-000000000001' FROM knowledge_document;
CREATE FUNCTION knowledge_chunk_review_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE doc TEXT;
BEGIN
    doc=CASE WHEN TG_OP='DELETE' THEN OLD.metadata->>'document_id' ELSE NEW.metadata->>'document_id' END;
    UPDATE knowledge_source SET revision=revision+1 WHERE document_id::text=doc;
    RETURN NULL;
END $$;
CREATE TRIGGER knowledge_chunk_review AFTER INSERT OR UPDATE OR DELETE ON vector_store FOR EACH ROW EXECUTE FUNCTION knowledge_chunk_review_changed();

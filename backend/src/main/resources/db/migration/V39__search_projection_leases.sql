ALTER TABLE knowledge_search_outbox ADD COLUMN claim_token UUID;
ALTER TABLE knowledge_search_outbox ADD COLUMN available_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE knowledge_search_outbox ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
CREATE INDEX knowledge_search_outbox_ready ON knowledge_search_outbox(available_at,revision);
CREATE FUNCTION reset_search_projection_claim() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.revision<>OLD.revision THEN
        NEW.claim_token=NULL; NEW.available_at=now(); NEW.attempts=0;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER search_projection_reset BEFORE UPDATE OF revision ON knowledge_search_outbox
FOR EACH ROW EXECUTE FUNCTION reset_search_projection_claim();

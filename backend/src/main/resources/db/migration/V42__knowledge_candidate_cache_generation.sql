CREATE TABLE knowledge_candidate_generation (
    id INTEGER PRIMARY KEY CHECK (id=1),
    generation UUID NOT NULL
);
INSERT INTO knowledge_candidate_generation VALUES (1, gen_random_uuid());

CREATE FUNCTION bump_knowledge_candidate_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE knowledge_candidate_generation SET generation=gen_random_uuid() WHERE id=1;
    RETURN NULL;
END $$;

CREATE TRIGGER candidate_generation_vectors AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
ON vector_store FOR EACH STATEMENT EXECUTE FUNCTION bump_knowledge_candidate_generation();
CREATE TRIGGER candidate_generation_documents AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
ON knowledge_document FOR EACH STATEMENT EXECUTE FUNCTION bump_knowledge_candidate_generation();
CREATE TRIGGER candidate_generation_sources AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE
ON knowledge_source FOR EACH STATEMENT EXECUTE FUNCTION bump_knowledge_candidate_generation();

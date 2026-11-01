CREATE TABLE chat_context_preference (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    budget_tokens INTEGER NOT NULL CHECK (budget_tokens > 0)
);

CREATE TABLE reminder_broadcast_state (
    id INTEGER PRIMARY KEY CHECK (id=1),
    digest VARCHAR(64) NOT NULL
);

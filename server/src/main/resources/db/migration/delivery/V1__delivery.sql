-- CYP-220 Phase 6 S6 — DeliveryLog (delivery) PG schema. NON-SECRET: the per-recipient delivered-message-id
-- set (CYP-132). APPEND-ONLY, set-add idempotent — the composite PK IS the set, so markDelivered is a single
-- INSERT .. ON CONFLICT DO NOTHING (no counter/identity → no import realign). Dedup over message_id (not a
-- positional ordinal), so a reset MessageStore + a surviving log never skips a NEW message.
CREATE TABLE IF NOT EXISTS delivery (
    project_id TEXT NOT NULL,
    agent_id   TEXT NOT NULL,
    message_id TEXT NOT NULL,
    PRIMARY KEY (project_id, agent_id, message_id)
);

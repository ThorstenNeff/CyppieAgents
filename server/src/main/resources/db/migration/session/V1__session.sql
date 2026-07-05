-- CYP-220 Phase 6 S6 — SessionStore (session) PG schema. NON-SECRET: the (projectId, agentId) → session_id
-- resume binding (CYP-167). One row per (project_id, agent_id). The createdAt-preserve merge is done in a single
-- atomic ON CONFLICT DO UPDATE statement (no counter/identity here → no import realign needed).
CREATE TABLE IF NOT EXISTS session (
    project_id    TEXT   NOT NULL,
    agent_id      TEXT   NOT NULL,
    session_id    TEXT   NOT NULL,
    created_at    BIGINT NOT NULL,
    last_activity BIGINT NOT NULL,
    PRIMARY KEY (project_id, agent_id)
);

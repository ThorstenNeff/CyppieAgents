-- CYP-220 Phase 6 S4 — AgentOverrideStore PG schema. NON-SECRET (display name/colour + connector
-- persona/launch + the polymorphic avatar override). One row per (project_id, agent_id); the whole
-- AgentOverride is stored as its canonical CommJson form so the polymorphic avatar round-trips exactly.
-- project_id is a column (multi-project), so a project cascade-delete scopes to exactly its rows.
CREATE TABLE IF NOT EXISTS agent_override (
    project_id    TEXT NOT NULL,
    agent_id      TEXT NOT NULL,
    override_json TEXT NOT NULL,
    PRIMARY KEY (project_id, agent_id)
);

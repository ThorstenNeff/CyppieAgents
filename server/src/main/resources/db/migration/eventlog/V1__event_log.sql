-- CYP-220 Phase 6 S5 — EventSink (event_log) PG schema. NON-SECRET, content-free metadata (PRD §3.5).
-- APPEND-ONLY: seq is stamped by the injected TimeSource (never DB-derived) so it is the explicit PRIMARY KEY,
-- gapless + total-ordered + restart-resumed above MAX(seq). project_id is a clean column (the sqlite impl kept
-- the legacy physical name team_id for migration-free old DBs; a fresh PG table has no such constraint).
CREATE TABLE IF NOT EXISTS events (
    seq            BIGINT PRIMARY KEY,
    id             TEXT   NOT NULL,
    ts             BIGINT NOT NULL,
    source_ts      BIGINT,
    agent_id       TEXT   NOT NULL,
    project_id     TEXT   NOT NULL,
    session_id     TEXT,
    correlation_id TEXT,
    type           TEXT   NOT NULL,
    severity       TEXT   NOT NULL,
    detail         TEXT   NOT NULL
);

-- Browse/filter axes (PRD §4) + the active-project scope (S13). seq is the PK so ORDER BY seq / seq > ? paging
-- is index-served; these cover the other Browse axes at volume.
CREATE INDEX IF NOT EXISTS idx_events_agent_seq ON events (agent_id, seq);
CREATE INDEX IF NOT EXISTS idx_events_type_seq ON events (type, seq);
CREATE INDEX IF NOT EXISTS idx_events_project_seq ON events (project_id, seq);
CREATE INDEX IF NOT EXISTS idx_events_corr ON events (correlation_id);
CREATE INDEX IF NOT EXISTS idx_events_session ON events (session_id);
CREATE INDEX IF NOT EXISTS idx_events_ts ON events (ts);

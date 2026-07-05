-- CYP-220 Phase 6 S6 — ReportStore (report) PG schema. NON-SECRET: content-free Product-Lead report snapshots
-- (S16 / CYP-89). APPEND-ONLY immutable snapshots; the whole ReportSnapshot is stored as canonical CommJson
-- (S4 pattern). id = the monotonic 'rep-N' (in-process counter, resumed above MAX on open → import realign axis).
-- Low volume (operator-generated) → generated_at index is enough for the newest-first list().
CREATE TABLE IF NOT EXISTS report (
    id            TEXT   PRIMARY KEY,
    project_id    TEXT   NOT NULL,
    generated_at  BIGINT NOT NULL,
    snapshot_json TEXT   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_report_generated_at ON report (generated_at);

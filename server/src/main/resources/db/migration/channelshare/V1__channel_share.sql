-- CYP-220 Phase 6 S4 — ChannelShareStore PG schema. NON-SECRET (a cross-project authorization gate, no
-- credentials). One row per channel_id (per-channel granularity — a share never widens to the owner's other
-- channels); the whole ChannelShareRecord is stored as its canonical CommJson form so the sharedWith/consents
-- sets round-trip exactly. owner_project_id is a column for cascade/inspection.
CREATE TABLE IF NOT EXISTS channel_share (
    channel_id       TEXT PRIMARY KEY,
    owner_project_id TEXT NOT NULL,
    record_json      TEXT NOT NULL
);

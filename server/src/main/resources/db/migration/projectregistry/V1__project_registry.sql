-- CYP-220 Phase 3 — ProjectRegistry PG schema (Design §2). Non-secret; clear PK.
-- `seq` preserves insertion order (the File impl's LinkedHashMap order) for a stable list view.
CREATE TABLE IF NOT EXISTS project (
    project_id TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    seq        BIGSERIAL NOT NULL
);

-- The active-project pointer as a single-row table (CHECK pins exactly one row).
CREATE TABLE IF NOT EXISTS project_active (
    only_one          BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (only_one),
    active_project_id TEXT NOT NULL
);

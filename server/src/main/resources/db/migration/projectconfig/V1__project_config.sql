-- CYP-220 Phase 6 S2 — ProjectConfigStore PG schema. The API key is stored ENCRYPTED (SecretCipher):
-- api_key_ct is the AEAD ciphertext, api_key_ver the key version. repo_url/branch are non-secret.
-- Plaintext API key NEVER at rest here.
CREATE TABLE IF NOT EXISTS project_config (
    project_id  TEXT PRIMARY KEY,
    repo_url    TEXT,
    repo_branch TEXT,
    api_key_ct  BYTEA,
    api_key_ver INTEGER
);

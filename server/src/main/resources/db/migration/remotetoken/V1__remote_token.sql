-- CYP-220 Phase 6 S2 — RemoteTokenStore PG schema. The bearer token is stored ENCRYPTED (SecretCipher):
-- token_ct is the AEAD ciphertext (wrapped-DEK embedded for the KMS path), token_ver the key version.
-- Plaintext token NEVER at rest here.
CREATE TABLE IF NOT EXISTS remote_token (
    agent_id  TEXT PRIMARY KEY,
    token_ct  BYTEA NOT NULL,
    token_ver INTEGER NOT NULL
);

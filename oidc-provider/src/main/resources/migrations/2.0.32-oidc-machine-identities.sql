-- 2.0.32 — Machine identities (service accounts) in the OIDC user directory.
--
-- Complements 2.0.31 (which modelled ZITADEL's users14 + humans child but for humans only)
-- with ZITADEL's machine vertical split: the user table becomes the unified identity main
-- table carrying a `type` discriminator (human / machine), and machine-specific fields live
-- in a dedicated child table oidc_user_machine (ZITADEL users14_machines: name/description/
-- access_token_type).
--
-- Rationale (dsh.docx 身份类型需求):
--   * identities are split between interactive users (human, authorization_code+PKCE login)
--     and service accounts (machine, client_credentials);
--   * machines were previously invisible to the directory: they lived only in oidc_client
--     with NO roles and NO state, so their roles fell back to the configured default-roles
--     ([admin]) at token time — an implicit admin that could not be managed or revoked.
--   * after this migration a machine is a first-class directory row: roles are persisted in
--     oidc_user.roles, state in oidc_user.user_state (INACTIVE revokes client_credentials),
--     and oidc_client remains the machine's credential/authorisation record (secret,
--     redirect_uris, grant_types, scopes) keyed by client_id = oidc_user.username.
--
-- Existing client_credentials clients (dsh, nacos, kestra-self) are migrated as machine
-- rows. Their roles are explicitly persisted as [admin] — the same effective role they had
-- via the default-roles fallback — so behaviour is unchanged but now explicit and editable.

-- 1) Discriminator on the unified identity main table.
ALTER TABLE oidc_user ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'human';
COMMENT ON COLUMN oidc_user.type IS
    'Identity type (ZITADEL users14.type): human (interactive, password/PKCE login) or machine (service account, client_credentials)';
CREATE INDEX IF NOT EXISTS idx_oidc_user_type ON oidc_user (type);

-- 2) Machine child table (ZITADEL users14_machines). The client secret stays in oidc_client.
CREATE TABLE IF NOT EXISTS oidc_user_machine (
    user_id           TEXT        PRIMARY KEY REFERENCES oidc_user(username) ON DELETE CASCADE,
    name              TEXT        NOT NULL,               -- machine display name (ZITADEL machine name)
    description       TEXT,                               -- free-text purpose (ZITADEL machine description)
    access_token_type TEXT        NOT NULL DEFAULT 'bearer', -- ZITADEL machine access_token_type
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE oidc_user_machine IS
    'Machine (service account) profile child of oidc_user (ZITADEL users14_machines). user_id = oidc_client.client_id.';

-- 3) Migrate existing confidential client_credentials clients as machine identities.
--    email is NOT NULL on oidc_user; machines have no real inbox, use a stable placeholder.
INSERT INTO oidc_user (username, name, email, user_state, roles, type)
SELECT c.client_id, c.client_id, c.client_id || '@machine.local', 'ACTIVE',
       '["admin"]'::jsonb, 'machine'
FROM oidc_client c
WHERE c.grant_types ? 'client_credentials'
  AND NOT EXISTS (SELECT 1 FROM oidc_user u WHERE u.username = c.client_id)
ON CONFLICT (username) DO NOTHING;

INSERT INTO oidc_user_machine (user_id, name, description, access_token_type)
SELECT c.client_id, c.client_id,
       'Migrated service account (client_credentials client)',
       'bearer'
FROM oidc_client c
WHERE c.grant_types ? 'client_credentials'
  AND NOT EXISTS (SELECT 1 FROM oidc_user_machine m WHERE m.user_id = c.client_id)
ON CONFLICT (user_id) DO NOTHING;

-- 2.0.37 — Add active flag to oidc_client (standard OAuth2 client enable/disable).
--
-- Allows enabling/disabling an OIDC client (machine identity / service account).
-- An inactive client is refused at the token endpoint (client_credentials grant).
-- This is a standard OAuth2 client management capability, not a custom hack.

ALTER TABLE oidc_client ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT true;

-- 2.0.31 — OIDC user directory in PostgreSQL (single-tenant, ZITADEL-aligned).
--
-- Design: models ZITADEL's user domain (users14 + humans child + user_auth_methods)
-- as two plain relational tables, adapted for a single tenant:
--   * oidc_user             — the user (Human) record. Domain fields mirror ZITADEL
--                             (username/email/email_verified/phone/phone_verified/
--                             user_state/password_change_required/last_login/roles).
--                             The event-sourcing mechanism columns (sequence,
--                             instance_id, resource_owner) are deliberately dropped —
--                             they exist only to replay ZITADEL events and would be
--                             recreated by ZITADEL itself on any future migration.
--   * oidc_user_auth_method — authentication methods per user (ZITADEL
--                             user_auth_methods5). A PASSWORD row carries the bcrypt
--                             credential in `credential`; future TOTP/passkey/OTP
--                             rows reuse the same shape. ZITADEL stores the hash in
--                             events, we materialise it here (storage decision only).
--
-- Roles: ZITADEL grants roles via memberships (member.roles text[]). Single tenant has
-- no org/project resources, so the role array lives directly on the user — same
-- semantics, same JSON array shape. A future ZITADEL import maps oidc_user.roles to the
-- instance member roles.
--
-- Seeding is done by OidcUserService bootstrap (from kestra.oidc.* config), not here —
-- passwords must be bcrypt-hashed at runtime.

CREATE TABLE oidc_user (
    username                TEXT        PRIMARY KEY,            -- = OIDC sub / login name
    name                    TEXT        NOT NULL,               -- display name (ZITADEL display_name)
    first_name              TEXT,
    last_name               TEXT,
    email                   TEXT        NOT NULL,
    email_verified          BOOLEAN     NOT NULL DEFAULT false, -- ZITADEL is_email_verified
    phone                   TEXT,
    phone_verified          BOOLEAN     NOT NULL DEFAULT false, -- ZITADEL is_phone_verified
    preferred_language      TEXT,                               -- ZITADEL preferred_language
    avatar_key              TEXT,                               -- ZITADEL avatar_key
    user_state              TEXT        NOT NULL DEFAULT 'ACTIVE', -- ZITADEL user_state (ACTIVE/INACTIVE)
    password_change_required BOOLEAN    NOT NULL DEFAULT false, -- ZITADEL password_change_required
    last_login_at           TIMESTAMPTZ,                        -- ZITADEL last_login
    roles                   JSONB       NOT NULL DEFAULT '["user"]'::jsonb, -- member.roles semantics
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE oidc_user IS
    'OIDC user directory (single-tenant, ZITADEL-aligned). username is the OIDC sub.';

CREATE TABLE oidc_user_auth_method (
    id          BIGSERIAL   PRIMARY KEY,
    user_id     TEXT        NOT NULL REFERENCES oidc_user(username) ON DELETE CASCADE,
    type        TEXT        NOT NULL,               -- PASSWORD / TOTP / OTP_EMAIL / OTP_SMS / PASSKEY
    state       TEXT        NOT NULL DEFAULT 'ACTIVE', -- ZITADEL state
    credential  TEXT,                               -- PASSWORD rows: bcrypt hash; TOTP: shared secret
    token_id    TEXT,                               -- ZITADEL token_id (WebAuthn/U2F device token)
    name        TEXT,                               -- display name of the method (e.g. "手机")
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, type)
);

COMMENT ON TABLE oidc_user_auth_method IS
    'Authentication methods per user (ZITADEL user_auth_methods5). PASSWORD rows carry the bcrypt hash.';

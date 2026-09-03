-- 2.0.36 — Separate machine identities (OIDC clients) from human users.
--
-- Problem: nacos / dsh / kestra-self were stored as oidc_user rows (type=machine)
-- and bound to roles via oidc_role_assignment, so they appeared in the user directory
-- and role assignment UI. But they are really OIDC clients (Applications) — their
-- roles belong to the client, not to a pseudo-user.
--
-- Fix:
--   1. Add `roles` JSONB column to oidc_client — client-scoped roles for
--      client_credentials grant (replaces the machine-identity user hack).
--   2. Migrate each machine identity's project roles into its client row.
--   3. Delete machine identity rows from oidc_user (and cascade their
--      oidc_role_assignment rows).
--   4. client_credentials flow now reads roles from the client row, not from
--      a user directory lookup.

-- ----------------------------------------------------------------------------
-- 1. Add roles column to oidc_client
--    roles: client-scoped roles for client_credentials grant (standard OAuth2:
--    the client itself is the resource owner in client_credentials flow)
-- ----------------------------------------------------------------------------
ALTER TABLE oidc_client ADD COLUMN IF NOT EXISTS roles JSONB NOT NULL DEFAULT '[]'::jsonb;

-- ----------------------------------------------------------------------------
-- 2. Migrate machine identity roles into client rows
--    For each machine user in oidc_user, collect its role assignments in the
--    dsh project and write them into the matching oidc_client.roles.
-- ----------------------------------------------------------------------------
UPDATE oidc_client c
SET roles = COALESCE((
    SELECT jsonb_agg(DISTINCT ra.role_name)
    FROM oidc_role_assignment ra
    WHERE ra.user_id = c.client_id
      AND ra.project_id = 'dsh'
), '[]'::jsonb)
WHERE c.client_id IN (
    SELECT username FROM oidc_user WHERE type = 'machine'
);

-- ----------------------------------------------------------------------------
-- 3. Ensure built-in clients have the correct roles (idempotent)
--    - nacos: admin + authenticated (Nacos OIDC plugin recognises "admin" as global admin)
--    - dsh: authenticated (dsh container API access, data scoped by token subject)
--    - kestra-self: authenticated (Kestra internal API calls)
--    - dsh-ui: [] (public SPA, authorization_code flow only)
--    - dsh-pc: [] (public PC app, authorization_code flow only)
-- ----------------------------------------------------------------------------
UPDATE oidc_client SET roles = '["admin","authenticated"]'::jsonb WHERE client_id = 'nacos';
UPDATE oidc_client SET roles = '["authenticated"]'::jsonb WHERE client_id = 'dsh';
UPDATE oidc_client SET roles = '["authenticated"]'::jsonb WHERE client_id = 'kestra-self';

-- ----------------------------------------------------------------------------
-- 4. Delete machine identity rows from oidc_user
--    (ON DELETE CASCADE removes their oidc_role_assignment rows)
-- ----------------------------------------------------------------------------
DELETE FROM oidc_user WHERE type = 'machine';

-- 2.0.33 — Machine identities default to the identity-only "authenticated" role.
--
-- kestra-self is a pure authentication client (authorization-code self-bootstrap for Kestra
-- itself; it never runs client_credentials), so it does not need kestra-admin. dsh and nacos
-- keep ["admin"] because their consumers contract on it:
--   * dsh   — the Worker DshStore reads/writes the full observation centre; the dsh
--             session/approval/metrics APIs owner-scope non-admin callers;
--   * nacos — the Nacos OIDC plugin maps the token roles claim (OIDC_ADMIN_ROLE=admin) to
--             its own admin principal (app-scripts/nacos/init.sh).
--
-- New machine identities get AUTHENTICATED_ROLE at creation (OidcUserService), so this is a
-- one-time data fix for the pre-existing seed.
UPDATE oidc_user
SET roles = '["authenticated"]'::jsonb, updated_at = now()
WHERE username = 'kestra-self' AND type = 'machine';

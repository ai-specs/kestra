-- 2.0.34 — Machine identities are identity-only; no machine is an administrator.
--
-- 2.0.33 converged kestra-self to "authenticated" but deliberately kept dsh and nacos on
-- ["admin"]. That design was rejected during review: a machine identity is never an
-- administrator in this IdP's directory. Kestra's directory role for a machine expresses
-- identity ("authenticated"), never elevation; whatever a consumer needs beyond identity is
-- served out-of-band:
--   * dsh   — full observation-centre access now derives from being a service identity
--             (sub == client_id, client_credentials), not from the admin role. The dsh
--             session/approval/metrics APIs owner-scope only human non-admin callers
--             (DshApprovalController, DshMetricsController: isService() || isAdmin()).
--   * nacos — the Nacos OIDC plugin still needs a roles claim to derive its admin
--             (OIDC_ADMIN_ROLE=admin). It is served at token-issue time by the per-client
--             token-roles override (OidcConfiguration.clientTokenRolesOverride), so the
--             directory row stays identity-only while the nacos client token carries
--             ["authenticated","admin"] for the Nacos plugin to match.
--
-- This is a one-time data fix for the pre-existing seed; new machine identities already
-- default to AUTHENTICATED_ROLE at creation (OidcUserService).
UPDATE oidc_user
SET roles = '["authenticated"]'::jsonb, updated_at = now()
WHERE type = 'machine' AND roles ? 'admin';

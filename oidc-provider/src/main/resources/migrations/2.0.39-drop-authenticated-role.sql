-- 2.0.39 — Drop the "authenticated" role.
--
-- Machine identities (nacos / dsh / kestra-self) were separated into pure OIDC
-- clients in 2.0.36; their roles now live on oidc_client.roles (standard OAuth2
-- client_credentials). The "authenticated" role was a leftover from the
-- machine-identity-as-user hack ("identity-only, no authorization (machine
-- identity default)") and is no longer meaningful:
--   - no human user is assigned it (oidc_role_assignment has 0 rows),
--   - client_credentials tokens are derived from oidc_client.roles, which no
--     longer need an "authenticated" marker.
-- So the role is dropped and "authenticated" is removed from client roles.

DELETE FROM oidc_role WHERE role_name = 'authenticated';

-- Remove "authenticated" from every client's roles (idempotent, order-preserving).
UPDATE oidc_client
SET roles = (
    SELECT COALESCE(jsonb_agg(v ORDER BY v), '[]'::jsonb)
    FROM jsonb_array_elements_text(roles) AS v
    WHERE v <> 'authenticated'
)
WHERE roles ? 'authenticated';

-- Normalize the kestra-self redirect URI to the provider's own callback path.
--
-- History: 2.0.25 originally seeded /oidc/callback, was briefly changed (in the source,
-- never re-applied) to the Micronaut-conventional /oauth/callback/kestra-oidc. That
-- namespace belongs to the framework's native OAuth2 client, whose template is the
-- PathSegment /oauth/callback{/provider} — it matches the bare /oauth/callback root AND
-- any depth of sub-path, so the ENTIRE /oauth/** namespace is off-limits to provider
-- controllers. Canonical path: /oidc/callback (inside the provider's own anonymous
-- /oidc/** namespace).
UPDATE oidc_client
SET redirect_uris = '["http://localhost:18080/oidc/callback"]'::jsonb
WHERE client_id = 'kestra-self'
  AND NOT (redirect_uris ? 'http://localhost:18080/oidc/callback');

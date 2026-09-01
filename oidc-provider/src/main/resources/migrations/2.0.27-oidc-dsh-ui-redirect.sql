-- Register the dsh-ui (mobile PWA) BFF callback on the dsh client (idempotent append).
UPDATE oidc_client
SET redirect_uris = redirect_uris || '["http://localhost:13010/auth/callback"]'::jsonb
WHERE client_id = 'dsh'
  AND NOT (redirect_uris ? 'http://localhost:13010/auth/callback');

-- ============================================================================
-- Kestra OIDC/OAuth2 Provider schema (enterprise unified IdP)
-- Applied through the Kestra migration mechanism (MigrationScript, id 2.0.25-oidc-provider).
-- Backend: PostgreSQL. Table prefix `oidc_`.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- OAuth2/OIDC clients
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oidc_client
(
    client_id      TEXT        NOT NULL PRIMARY KEY,
    client_secret  TEXT        NOT NULL,
    redirect_uris  JSONB       NOT NULL DEFAULT '[]'::jsonb,
    grant_types    JSONB       NOT NULL DEFAULT '["authorization_code","client_credentials","refresh_token"]'::jsonb,
    scopes         JSONB       NOT NULL DEFAULT '["openid","profile","email"]'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- Authorization codes (PKCE-aware)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oidc_authorization_code
(
    code                  TEXT        NOT NULL PRIMARY KEY,
    client_id             TEXT        NOT NULL REFERENCES oidc_client (client_id),
    subject               TEXT        NOT NULL,
    redirect_uri          TEXT        NOT NULL,
    scopes                JSONB       NOT NULL DEFAULT '[]'::jsonb,
    code_challenge        TEXT,
    code_challenge_method TEXT,
    nonce                 TEXT,
    expires_at            TIMESTAMPTZ NOT NULL,
    used                  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_oidc_authz_code_client ON oidc_authorization_code (client_id);

-- ----------------------------------------------------------------------------
-- Tokens (access = RS256 JWT, refresh = opaque). `value` stores the raw token.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oidc_token
(
    id         BIGSERIAL   NOT NULL PRIMARY KEY,
    client_id  TEXT        NOT NULL REFERENCES oidc_client (client_id),
    subject    TEXT,
    token_type TEXT        NOT NULL,                    -- 'access' | 'refresh'
    value      TEXT        NOT NULL,
    scopes     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    issued_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_oidc_token_value ON oidc_token (value);
CREATE INDEX IF NOT EXISTS idx_oidc_token_client ON oidc_token (client_id);
CREATE INDEX IF NOT EXISTS idx_oidc_token_type ON oidc_token (token_type);

-- ----------------------------------------------------------------------------
-- JSON Web Keys (signing keys for RS256)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oidc_jwk
(
    kid        TEXT        NOT NULL PRIMARY KEY,
    jwk        TEXT        NOT NULL,                    -- full RSA JWK JSON (incl. private part for signing)
    algorithm  TEXT        NOT NULL DEFAULT 'RS256',
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================================
-- Seed data
-- ============================================================================

-- Default RSA-2048 signing key (RS256). kid: dsh-oidc-default-2026.
INSERT INTO oidc_jwk (kid, jwk, algorithm, active)
VALUES ('dsh-oidc-default-2026',
        '{"kty":"RSA","kid":"dsh-oidc-default-2026","use":"sig","alg":"RS256","n":"zx6VczV0Qq8kpkVTGkgKHfzOivmj3jDlh0A1Tjmfav5RFBHMmZ2agPD_bR_MFQLgnlrHlDwcKl1fnkRhzq1h07RJJA1cU-3o6GLdZPjKy1Bn4KwdnvJX6XmpeiwSthrq2t6RvZcS3dJlhgloXCGPvmoapKkgNXfnvsSykOGEYWs6rcR0D8aE1DjjAS5BDLZFX3sz9HbPMW_atdA4RcCehdqL9catWrcemyVgw-AUD_qyvDtOtV-3rtkS0F5w9GI4qZywC5DkyRnNeR-Ap9o-nWSgQNTWiNJvX96cOHdE0vUNhKyOCaiX2L6ZJ2YWZShkzB4k66rP0VHhGCVcnM4_OQ","e":"AQAB","d":"KrMMbob7o-_Fp32tR0LIyyveTOpwbRM5jqufEbRxeDZb8r5EpvrF6oVEs3OVuFMyiZL_iEDoMZe0acWBQIGgJGwLlCIpAaiPlrEjIXjlHgtCjyAMr0MY-qo5VVWWufJMrrip5SMrIY4Jnht8Z1oH72KigYXYfQ1uOncEtTe4-fgo7BWXZ9EtIRjTfVAfgjWV-HTttWdHDtWlZf6w87c8XCRFpeoD6HPEoWHqEPkoJxfX1tgxP6f0uvfWY3SdBlEX4MJxI8UR1hMc8Ngh8tjJlwxJnbkWv5XvE6NU5dOPsnd0JG5i22E15F5-P75pOZpRwRloEt7yUhO5n6pyk76U8Q","p":"_XSluQcoKUGkQk1gf3_AbOkdVujrW2xiAz0D1N3Zio2AE3LXISRJvUVxUZQH40GqSuvkItkvaqxdZ0XfgX93w8gksiCPT6U5sAOkdg2yZ0mwO7bguZkMW8gNbpf9PkvBUNPaAruJGbZSwHqUYkLkWCNJQ6ZdwXvsdOt7-RJIofU","q":"0TLbjWZ5PZLPDQD3JA1xE6wt9gL7EDeiprBU9ots-o3pxl0CWNZGU-rFrAlgdXeNrt96ntFPFU4vx0sne8OyiepPp40ZKSfMbaVIqfOEZDlQJdKHNnI26lo2Ab3MeacbW-iwTO8x1CoH58pr7-TibM9stATNoVjTJ0FBm8_FqbU","dp":"gPQYXUuG43bCW_mCX1xgSwlQJCfdl2ZNd9hTWrqFNDlKMJr9WZ9c5S-vuBHY8Yc3XWcna5OUzHFmFGU4kmqIRNRP11E5mQw3jZZM1sOhQWv8fmVNcSCtaQUf0GUEM-3XLYicI0fHOY3KDKRRWq0uAKnzXLgIe6jRZatqKA9lZQE","dq":"VSroWWvDpj6zjq667vGN_J1XZNWX0uVjkYsuO-VNa8AE0Z4iC3rIzPdCnKSAobIXe38-E5RRJvJa3z8IhwmIET_UR_lYqfmq72Sf7ZjXaFow-AT6yT68frJVVGYp7ckLiWJ1DA1Bcwfdig9N3C-JwxS7Q5rFAJAB_vRBOqbe6RU","qi":"wlb4pWi7o6EZVUp7FUhY7xwGafEuYCn1mAr5BaN90DbvNdodg6M82S3nzSyICMU2TRAxJCqv0Wv4MIawpZQ9qsUooD7iauOKv1pJi89ZXsdBvZCAHttAAkAe38KvE6yqkdWyUPj4ds7MBhqOUu0xvc1OZ12g-xEpmLczP80sg0g"}',
        'RS256', TRUE)
ON CONFLICT (kid) DO NOTHING;

-- Default OAuth2/OIDC clients: nacos, dsh, kestra-self.
-- Secrets here are DEVELOPMENT-ONLY defaults; override via `kestra.oidc` seeding or CLI if needed.
INSERT INTO oidc_client (client_id, client_secret, redirect_uris, grant_types, scopes)
VALUES ('nacos',
        'nacos-secret-change-me',
        '["http://localhost:18480/v1/auth/oidc/callback"]'::jsonb,
        '["authorization_code","client_credentials","refresh_token"]'::jsonb,
        '["openid","profile","email"]'::jsonb),
       ('dsh',
        'dsh-secret-change-me',
        '["http://localhost:18080/api/v1/dsh/oidc/callback"]'::jsonb,
        '["authorization_code","client_credentials","refresh_token"]'::jsonb,
        '["openid","profile","email"]'::jsonb),
       ('kestra-self',
        'kestra-self-secret-change-me',
        '["http://localhost:18080/oidc/callback"]'::jsonb,
        '["authorization_code","client_credentials","refresh_token"]'::jsonb,
        '["openid","profile","email"]'::jsonb)
ON CONFLICT (client_id) DO NOTHING;

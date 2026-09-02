-- ============================================================================
-- dsh-ui(手机)/dsh-pc(PC) 公开 OIDC 客户端 + dsh 会话所有者归属（dsh.docx：
-- 统一认证「用户接入端一律 Authorization Code + PKCE(S256)，客户端不持有
-- client_secret」；跨端同步原理「会话记录绑定所有者用户身份（OIDC sub）」）
-- ============================================================================

-- 公开客户端：client_secret 为空串即公开（token_endpoint_auth_method=none），
-- 以 PKCE(S256) 证明持有，不持有任何 secret。idempotent upsert。
INSERT INTO oidc_client (client_id, client_secret, redirect_uris, grant_types, scopes)
VALUES
    ('dsh-ui', '',
     '["http://localhost:13010/","http://127.0.0.1:13010/","http://localhost:5173/","dshui://oauth/callback"]'::jsonb,
     '["authorization_code","refresh_token"]'::jsonb,
     '["openid","profile","email"]'::jsonb),
    ('dsh-pc', '',
     '["http://127.0.0.1:14100/callback","http://localhost:14100/callback"]'::jsonb,
     '["authorization_code","refresh_token"]'::jsonb,
     '["openid","profile"]'::jsonb)
ON CONFLICT (client_id) DO UPDATE
    SET client_secret = '',
        redirect_uris = EXCLUDED.redirect_uris,
        grant_types = EXCLUDED.grant_types,
        scopes = EXCLUDED.scopes;

-- 会话所有者（OIDC sub）与手机端待处理输入。
-- dsh_session 表由 plugin-deepseek-harness 的 DshStore.ensureSchema 幂等创建
-- （首次连接时机晚于本 migration），故这里用 IF EXISTS/DO 块保护；新库由
-- ensureSchema 的更新版直接带全列。
ALTER TABLE IF EXISTS dsh_session ADD COLUMN IF NOT EXISTS owner TEXT;
ALTER TABLE IF EXISTS dsh_session ADD COLUMN IF NOT EXISTS pending_input TEXT;
ALTER TABLE IF EXISTS dsh_session ADD COLUMN IF NOT EXISTS input_at TIMESTAMPTZ;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'dsh_session') THEN
        CREATE INDEX IF NOT EXISTS idx_dsh_session_owner ON dsh_session(owner, updated_at DESC);
        -- 存量行回填：历史 user_id 视为所有者（兼容升级，避免旧会话对所有者不可见）
        UPDATE dsh_session SET owner = user_id WHERE owner IS NULL;
    END IF;
END $$;

-- ============================================================================
-- dsh-pc 追加 dsh web 的网页 OIDC 回调（dsh web auth=oidc 模式：
-- 浏览器 Authorization Code + PKCE(S256) 登录统一 IdP）
-- ============================================================================

-- 合并而非覆盖：保留 2.0.28 注册的 daemon loopback 回调（127.0.0.1:14100）。
-- 守卫条件 + DISTINCT 保证重复执行幂等（migration 历史表本就只跑一次，
-- 这里额外保证与手工 SQL/重放兼容）。
UPDATE oidc_client
SET redirect_uris = (
    SELECT COALESCE(jsonb_agg(DISTINCT v ORDER BY v), '[]'::jsonb)
    FROM jsonb_array_elements(
        redirect_uris
        || '["http://localhost:13000/oidc/callback", "http://127.0.0.1:13000/oidc/callback"]'::jsonb
    ) AS t(v)
)
WHERE client_id = 'dsh-pc'
  AND NOT redirect_uris @> '["http://localhost:13000/oidc/callback", "http://127.0.0.1:13000/oidc/callback"]'::jsonb;

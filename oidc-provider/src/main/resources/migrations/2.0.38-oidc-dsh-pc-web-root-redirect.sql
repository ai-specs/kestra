-- ============================================================================
-- dsh-pc 追加 dsh web 部署根回跳（RP-initiated logout 的
-- post_logout_redirect_uri 白名单校验要求回跳地址精确命中某个已注册
-- redirect_uri；dsh web 退出登录后需要回到部署根 → 登录页）
-- ============================================================================

-- 合并而非覆盖；守卫条件 + DISTINCT 保证重复执行幂等。
UPDATE oidc_client
SET redirect_uris = (
    SELECT COALESCE(jsonb_agg(DISTINCT v ORDER BY v), '[]'::jsonb)
    FROM jsonb_array_elements(
        redirect_uris
        || '["http://localhost:13000/", "http://127.0.0.1:13000/"]'::jsonb
    ) AS t(v)
)
WHERE client_id = 'dsh-pc'
  AND NOT redirect_uris @> '["http://localhost:13000/", "http://127.0.0.1:13000/"]'::jsonb;

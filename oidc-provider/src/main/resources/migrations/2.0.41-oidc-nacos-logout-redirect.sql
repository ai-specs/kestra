-- ============================================================================
-- nacos client 追加 Nacos 控制台根回跳（RP-initiated logout 的
-- post_logout_redirect_uri 白名单校验要求回跳地址精确命中某个已注册
-- redirect_uri；Nacos 退出登录后需要回到控制台根 → 重新登录）
-- ============================================================================

-- 合并而非覆盖；守卫条件 + DISTINCT 保证重复执行幂等。
UPDATE oidc_client
SET redirect_uris = (
    SELECT COALESCE(jsonb_agg(DISTINCT v ORDER BY v), '[]'::jsonb)
    FROM jsonb_array_elements(
        redirect_uris
        || '["http://localhost:18480/", "http://127.0.0.1:18480/"]'::jsonb
    ) AS t(v)
)
WHERE client_id = 'nacos'
  AND NOT redirect_uris @> '["http://localhost:18480/", "http://127.0.0.1:18480/"]'::jsonb;

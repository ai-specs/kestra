-- ============================================================================
-- Tailscale 跨机访问：为 dsh-ui / dsh-pc / nacos 追加 nip.io（及裸 Tailscale IP）
-- 回调变体。手机 App/H5 经 `100.71.119.22.nip.io` 指向宿主机 Tailscale IP，
-- 回调（redirect_uri / post_logout_redirect_uri）以访问时的 origin 动态生成，
-- 因此 IdP 侧必须把这些 origin 注册进对应 client 的 redirect_uris 白名单。
--
-- 主用 nip.io 域名（cookie 按 host 隔离、日志可读）；裸 IP 一并注册作备选。
-- 合并而非覆盖；守卫条件 + DISTINCT 保证重复执行幂等（与 2.0.30/2.0.38 同模式）。
-- ============================================================================

-- dsh-ui（手机 H5 / App web-view 授权回跳 = 部署根路径）
UPDATE oidc_client
SET redirect_uris = (
    SELECT COALESCE(jsonb_agg(DISTINCT v ORDER BY v), '[]'::jsonb)
    FROM jsonb_array_elements(
        redirect_uris
        || '["http://100.71.119.22.nip.io:13010/", "http://100.71.119.22:13010/"]'::jsonb
    ) AS t(v)
)
WHERE client_id = 'dsh-ui'
  AND NOT redirect_uris @> '["http://100.71.119.22.nip.io:13010/", "http://100.71.119.22:13010/"]'::jsonb;

-- dsh-pc（dsh web：/oidc/callback 授权回调 + 部署根 RP-initiated logout 回跳）
UPDATE oidc_client
SET redirect_uris = (
    SELECT COALESCE(jsonb_agg(DISTINCT v ORDER BY v), '[]'::jsonb)
    FROM jsonb_array_elements(
        redirect_uris
        || '[
          "http://100.71.119.22.nip.io:13000/oidc/callback",
          "http://100.71.119.22.nip.io:13000/",
          "http://100.71.119.22:13000/oidc/callback",
          "http://100.71.119.22:13000/"
        ]'::jsonb
    ) AS t(v)
)
WHERE client_id = 'dsh-pc'
  AND NOT redirect_uris @> '[
    "http://100.71.119.22.nip.io:13000/oidc/callback",
    "http://100.71.119.22.nip.io:13000/",
    "http://100.71.119.22:13000/oidc/callback",
    "http://100.71.119.22:13000/"
  ]'::jsonb;

-- nacos（手机经 Tailscale 访问 Nacos 控制台时 SSO 授权回调与登出回跳）
UPDATE oidc_client
SET redirect_uris = (
    SELECT COALESCE(jsonb_agg(DISTINCT v ORDER BY v), '[]'::jsonb)
    FROM jsonb_array_elements(
        redirect_uris
        || '["http://100.71.119.22.nip.io:18480/", "http://100.71.119.22:18480/"]'::jsonb
    ) AS t(v)
)
WHERE client_id = 'nacos'
  AND NOT redirect_uris @> '["http://100.71.119.22.nip.io:18480/", "http://100.71.119.22:18480/"]'::jsonb;

-- ============================================================================
-- 运行时播种的用户补建项目角色指派。
--
-- 2.0.35 起 oidc_role_assignment 是角色的权威来源（oidc_user.roles 仅存展示
-- 副本），但 OidcUserService.seedConfiguredAccounts 的运行时引导只写旧列：
-- 全新环境里迁移先跑、用户后播种，指派表永远空 → 所有账号（含 admin）解析
-- 出的角色为空，用户/角色管理面陷入"需要 admin 角色才能进管理页发角色"的
-- 自举死锁。本迁移把存量用户的旧列角色补建成 dsh 项目指派；配合
-- seedUser 的同语句写入，两个来源合并后幂等。
-- ============================================================================

INSERT INTO oidc_role_assignment (user_id, project_id, role_name)
SELECT u.username, 'dsh', role
FROM oidc_user u
CROSS JOIN LATERAL jsonb_array_elements_text(u.roles) AS role
ON CONFLICT (user_id, project_id, role_name) DO NOTHING;

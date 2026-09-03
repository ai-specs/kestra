-- 2.0.35 — Project + project-level roles + role assignments (ZITADEL-aligned).
--
-- Introduces the Project abstraction: a project contains Applications (oidc_client)
-- and project-scoped Roles. A user's roles are bound via Role Assignment
-- (user + project + role), and the token's roles claim is issued from the
-- assignments in the project that the requesting client belongs to.
--
-- This replaces the global oidc_user.roles field with project-scoped assignments,
-- and retires the clientTokenRolesOverride hack (nacos admin is now a regular
-- project role assignment on the nacos machine identity).
--
-- Default project: "dsh" — contains all existing applications (kestra-self,
-- nacos, dsh, dsh-ui, dsh-pc) and the three built-in roles:
--   - admin        : dsh ecosystem admin (Kestra user/role mgmt + nacos global admin + dsh data full access)
--   - user         : dsh ecosystem regular user (Kestra normal features + dsh data scoped to self)
--   - authenticated: identity-only, no authorization (machine identity default)

-- ----------------------------------------------------------------------------
-- Project
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oidc_project
(
    id          TEXT        NOT NULL PRIMARY KEY,
    name        TEXT        NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- Project-scoped Role (unique within a project)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oidc_role
(
    project_id  TEXT        NOT NULL REFERENCES oidc_project (id) ON DELETE CASCADE,
    role_name   TEXT        NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, role_name)
);

-- ----------------------------------------------------------------------------
-- Role Assignment (user + project + role) — the authoritative source of a
-- user's roles within a project. Replaces oidc_user.roles (global).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oidc_role_assignment
(
    user_id     TEXT        NOT NULL REFERENCES oidc_user (username) ON DELETE CASCADE,
    project_id  TEXT        NOT NULL REFERENCES oidc_project (id) ON DELETE CASCADE,
    role_name   TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, project_id, role_name)
);

CREATE INDEX IF NOT EXISTS idx_oidc_role_assignment_user ON oidc_role_assignment (user_id);
CREATE INDEX IF NOT EXISTS idx_oidc_role_assignment_project ON oidc_role_assignment (project_id, role_name);

-- ----------------------------------------------------------------------------
-- Application (oidc_client) belongs to a Project
-- ----------------------------------------------------------------------------
ALTER TABLE oidc_client ADD COLUMN IF NOT EXISTS project_id TEXT REFERENCES oidc_project (id);

-- ----------------------------------------------------------------------------
-- Default project + built-in roles
-- ----------------------------------------------------------------------------
INSERT INTO oidc_project (id, name, description)
VALUES ('dsh', 'DSH Ecosystem', 'Default project containing all dsh applications: Kestra, Nacos, dsh PC/mobile')
ON CONFLICT (id) DO NOTHING;

INSERT INTO oidc_role (project_id, role_name, description)
VALUES
    ('dsh', 'admin', 'dsh ecosystem admin: Kestra user/role management + nacos global admin + dsh data full access'),
    ('dsh', 'user', 'dsh ecosystem regular user: Kestra normal features + dsh data scoped to self'),
    ('dsh', 'authenticated', 'identity-only, no authorization (machine identity default)')
ON CONFLICT (project_id, role_name) DO NOTHING;

-- ----------------------------------------------------------------------------
-- All existing applications belong to the dsh project
-- ----------------------------------------------------------------------------
UPDATE oidc_client SET project_id = 'dsh' WHERE project_id IS NULL;

-- ----------------------------------------------------------------------------
-- Migrate global oidc_user.roles → project-scoped role assignments (dsh project).
-- Each user's each global role becomes an assignment in the dsh project.
-- ----------------------------------------------------------------------------
INSERT INTO oidc_role_assignment (user_id, project_id, role_name)
SELECT u.username, 'dsh', role
FROM oidc_user u
CROSS JOIN LATERAL jsonb_array_elements_text(u.roles) AS role
ON CONFLICT (user_id, project_id, role_name) DO NOTHING;

-- ----------------------------------------------------------------------------
-- nacos machine identity: bind the admin role in the dsh project (replaces
-- clientTokenRolesOverride hack). The nacos OIDC plugin derives global admin
-- from the token roles claim containing "admin" (OIDC_ADMIN_ROLE=admin).
-- ----------------------------------------------------------------------------
INSERT INTO oidc_role_assignment (user_id, project_id, role_name)
VALUES ('nacos', 'dsh', 'admin')
ON CONFLICT (user_id, project_id, role_name) DO NOTHING;

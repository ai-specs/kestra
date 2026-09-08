-- 2.0.42 — dsh-managed secrets in PostgreSQL (OSS 叠加层，仿企业版能力)。
--
-- 背景：Kestra OSS 的 secret 只来自环境变量（SECRET_* 前缀 + Base64），UI 只读。
-- 企业版允许在 UI 增删改、按 namespace 组织。dsh 在 OSS 上叠加同等能力：
--   * dsh_secret        — UI 管理的 secret（按 tenant/namespace 组织），值经
--                         AES-256-GCM 加密后落库（主密钥 KESTRA_SECRETS_MASTER_KEY）。
--   * 读取语义          — flow 的 {{ secret('K') }} 先查本 namespace → 父 namespace
--                         （继承链）→ 回退环境变量 SECRET_*（部署级兜底，零破坏）。
--
-- 设计约束（对齐 docs 里 dsh 叠加原则）：不改动 core/webserver 开源代码——
-- SecretService 由 oidc-provider 的 DshSecretService 以 @Replaces 叠加替换；
-- 写端点/存储/加密全部位于 oidc-provider 模块。本表即该叠加层的数据底座。
--
-- 命名/类型约定与 oidc_* 表一致（TEXT 主键、timestamptz、jsonb）。

CREATE TABLE dsh_secret (
    tenant_id   TEXT        NOT NULL,
    namespace   TEXT        NOT NULL,               -- namespace；根 namespace 存 '.'（与 Kestra 约定一致）
    secret_key  TEXT        NOT NULL,               -- 大写 KEY 名（SecretService 语义为大写）
    secret_value BYTEA      NOT NULL,               -- AES-GCM 密文（IV(12) + ciphertext + tag 拼接）
    description TEXT,                               -- 可空：人类可读说明
    tags        JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, namespace, secret_key)
);

-- UI 管理列表 / namespace 继承链查询走该索引（tenant 恒为 main，单租户）。
CREATE INDEX idx_dsh_secret_lookup ON dsh_secret (tenant_id, namespace);

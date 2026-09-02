-- ============================================================================
-- Kestra dsh observation schema (dsh_session / dsh_approval / dsh_metrics)
-- Applied through the Kestra migration mechanism (MigrationScript, id 2.0.29-dsh-schema).
-- Backend: PostgreSQL.
--
-- Authoritative copy: the historical migrations/dsh-schema-postgres.sql (plugin
-- plugin-deepseek-harness) moved here when the dsh schema bootstrap moved from the
-- Worker JVM (DshStore.ensureSchema) to the control plane. Table structure is
-- unchanged — no column/type/index modifications versus the pre-migration schema.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- dsh_session — dsh.docx 会话存储；owner/pending_input/input_at 对旧库以
-- ADD COLUMN IF NOT EXISTS 补列（旧 ensureSchema 分两段建表，新库直接含全列）。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dsh_session (
    id            UUID PRIMARY KEY,
    user_id       TEXT,
    owner         TEXT,                     -- 所有者用户身份（OIDC sub）；仅同 sub 跨端互通
    phase         TEXT NOT NULL,            -- created/running/pending_approval/completed/failed
    state         JSONB,
    metadata      JSONB,
    pending_input TEXT,                     -- 手机端写入、PC 轮询消费的待处理输入
    input_at      TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- 旧库兼容：老 schema 的 dsh_session 缺 owner/pending_input/input_at 三列
ALTER TABLE dsh_session ADD COLUMN IF NOT EXISTS owner TEXT;
ALTER TABLE dsh_session ADD COLUMN IF NOT EXISTS pending_input TEXT;
ALTER TABLE dsh_session ADD COLUMN IF NOT EXISTS input_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_dsh_session_user  ON dsh_session(user_id);
CREATE INDEX IF NOT EXISTS idx_dsh_session_phase ON dsh_session(phase, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_dsh_session_owner ON dsh_session(owner, updated_at DESC);
-- legacy rows predating the owner column inherit the user id (dsh.docx 跨端同步兜底)
UPDATE dsh_session SET owner = user_id WHERE owner IS NULL;

-- ----------------------------------------------------------------------------
-- dsh_approval — 人工审批（refund/contract/data_export/...），超时由 webserver
-- 侧调度器自动拒绝。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dsh_approval (
    id              UUID PRIMARY KEY,
    session_id      UUID REFERENCES dsh_session(id),
    type            TEXT NOT NULL,          -- refund/contract/data_export/...
    payload         JSONB,
    approvers       TEXT[],
    status          TEXT NOT NULL,          -- PENDING/APPROVED/REJECTED
    approver        TEXT,
    comment         TEXT,
    timeout_seconds INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_dsh_approval_status ON dsh_approval(status, created_at DESC);

-- ----------------------------------------------------------------------------
-- dsh_metrics — 黄金指标（task_completion_rate/tool_error_rate/p99_latency_ms）
-- 由 Worker 插件 DshMetrics REPORT 与 AIAgent 经控制面 API 写入。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dsh_metrics (
    id                    BIGSERIAL PRIMARY KEY,
    session_id            UUID,
    user_id               TEXT,
    task_completion_rate  DOUBLE PRECISION, -- 黄金指标1：>=0.95
    tool_error_rate       DOUBLE PRECISION, -- 黄金指标2：<=0.001
    p99_latency_ms        BIGINT,           -- 黄金指标3：<=500
    token_usage           BIGINT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_dsh_metrics_session ON dsh_metrics(session_id, created_at DESC);

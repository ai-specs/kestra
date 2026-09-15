-- ============================================================================
-- Kestra dsh: dsh_metrics.session_id uuid → text
-- Applied through the Kestra migration mechanism (MigrationScript, id 2.0.31-dsh-metrics-session-id-text).
-- Backend: PostgreSQL.
--
-- 2026-09-15 方案 C：手机端 dsh 发起新会话时由手机端生成 sessionId（如
-- `session-<base36>`、e2e 的 `e2e-new-<ts>`），PC 采纳执行。此前新会话 id 由
-- PC 的 randomUUID() 生成（UUID 格式），dsh_metrics.session_id 的 UUID 约束可满足；
-- 方案 C 后非 UUID 会话 id 会触发 `invalid input syntax for type uuid`（
-- DshMetricsController 的 `?::uuid` 已同步移除）。会话 id 本就是任意业务标识，
-- 约束放宽为 text。
--
-- 幂等：对已为 text 的列执行 ALTER TYPE text 为 no-op。
-- ============================================================================

ALTER TABLE dsh_metrics ALTER COLUMN session_id TYPE text;

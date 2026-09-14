-- ============================================================================
-- Kestra dsh: drop option A tables (dsh_session / dsh_approval)
-- Applied through the Kestra migration mechanism (MigrationScript, id 2.0.30-dsh-drop-a-tables).
-- Backend: PostgreSQL.
--
-- 2026-09-14 维护者裁定：选项 A（中台存储会话全文 + PC 接力消费 pending_input）
-- 被选项 B（边端权威 + 中台纯转发）否决。A 组件已逐项退役（Controller 删除、
-- 端点 404、镜像关闭、中台写入移除）；本迁移完成**完全退役**的最后一环——
-- 删除 A 方案专用表。数据已备份（/tmp/dsh-a-tables-backup-*.sql）。
--
-- dsh_metrics 保留（选项 B 指标聚合仍用）；dsh_secret 保留（secrets 管理）。
-- 幂等：DROP TABLE IF EXISTS；迁移记录在 migration 历史中，不可重复执行后回滚。
-- ============================================================================

DROP TABLE IF EXISTS dsh_approval;
DROP TABLE IF EXISTS dsh_session;

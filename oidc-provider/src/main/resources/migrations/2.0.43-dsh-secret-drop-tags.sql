-- 2.0.43 — drop tags column from dsh_secret.
--
-- 用户确认：secrets 标签为纯元数据、不参与选取/隔离，且上游企业版是否含 tags 无法确认，
-- 予以删除；description 保留为唯一元数据说明。本脚本幂等（IF EXISTS），
-- 2.0.42 之后创建的库（建表已无 tags 列）执行无害。

ALTER TABLE dsh_secret DROP COLUMN IF EXISTS tags;

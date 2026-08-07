-- ============================================================
-- 分账记录表钱包聚合索引（D-421 R1-P2，2026-08-07）
--
-- 背景：机主钱包在途分润聚合按 (RECEIVER_USER_ID, SPLIT_STATUS) 过滤并取
-- MIN(CREATE_TIME)，既有索引只有 (RECEIVER_TYPE, RECEIVER_USER_ID)——复验
-- EXPLAIN 实测 type=ALL 全表扫描，钱包打开耗时随全平台分账记录量退化。
--
-- 本索引与查询列序一致：(RECEIVER_USER_ID, SPLIT_STATUS, CREATE_TIME)，
-- 过滤两列走索引、MIN(CREATE_TIME) 顺索引取首行。
--
-- 幂等：information_schema 探测后条件执行，重复执行零变更。
-- 注意：主库执行须单独授权（复验驳回意见三.5 口径——索引迁移属数据库变更）。
-- ============================================================

SET NAMES utf8mb4;

SET @exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ws_split_record'
    AND INDEX_NAME = 'idx_split_pending_wallet');

SET @ddl := IF(@exists = 0,
  'ALTER TABLE `ws_split_record` ADD INDEX `idx_split_pending_wallet` (`RECEIVER_USER_ID`, `SPLIT_STATUS`, `CREATE_TIME`)',
  'SELECT ''idx_split_pending_wallet already exists, skip'' AS note');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

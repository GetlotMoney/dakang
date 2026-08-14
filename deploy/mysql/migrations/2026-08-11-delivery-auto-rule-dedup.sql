-- ============================================================
-- 2026-08-11 · 自动补货规则同款去重（G7-B08）
--
-- 背景：ws_delivery_auto_rule 原本只有 uk_dauto_rule_key(RULE_KEY)，
-- 而 RULE_KEY = sha256(userId:requestId) 只防「同一次提交被重复提交」。
-- 用户每次在配送下单页选一次「自动补货」，就新增一条启用中的规则；
-- 同水种、同规格、同收货地址可以并存 N 条，Worker 每期给每条各扣一次款。
-- 用户以为自己设了一个补货，实际可能有五条在跑，首单钱也各扣了一次。
--
-- 做法：加一个「存活期同款去重键」生成列 + 唯一索引。
-- MySQL 没有条件唯一索引，用「不满足条件即 NULL」实现——NULL 之间不互斥，
-- 于是已取消(3)的规则不占位、同款可以重新建；启用(1)/停用(2)期间建不出第二条。
--
-- 刻意不含 DATA_STATUS：与本仓其它唯一键同口径，逻辑删不放开占位。
-- 让位只能由用户显式取消（RULE_STATUS=3，终态不可恢复）完成。
--
-- 幂等：ADD COLUMN / ADD INDEX 均先查 information_schema 再执行，可重复运行。
--
-- 执行前提：**必须先确认存量数据里没有同款并存的启用/停用规则**，
-- 否则加唯一索引会失败。本文件第 1 段就是这道体检，命中即中止并列出冲突。
-- ============================================================
SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1) 前置体检：存量是否已存在同款并存
-- ------------------------------------------------------------
SELECT '存量同款并存检查（应为 0 组；非 0 时请先人工取消多余规则再执行本迁移）' AS step;
SELECT USER_ID, WATER_TYPE_ID, CONTAINER_SPEC, RECEIVE_ADDRESS, COUNT(*) AS alive_count
FROM `ws_delivery_auto_rule`
WHERE `RULE_STATUS` IN (1, 2)
GROUP BY USER_ID, WATER_TYPE_ID, CONTAINER_SPEC, RECEIVE_ADDRESS
HAVING alive_count > 1;

-- ------------------------------------------------------------
-- 2) 生成列
-- ------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ws_delivery_auto_rule'
    AND COLUMN_NAME = 'ACTIVE_SHAPE_KEY');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE `ws_delivery_auto_rule` ADD COLUMN `ACTIVE_SHAPE_KEY` varchar(64)
     GENERATED ALWAYS AS (
       CASE WHEN `RULE_STATUS` IN (1, 2)
            THEN SHA2(CONCAT_WS('':'', `USER_ID`, `WATER_TYPE_ID`, `CONTAINER_SPEC`, `RECEIVE_ADDRESS`), 256)
            ELSE NULL END) STORED
     COMMENT ''存活期同款去重键：同用户+水种+规格+收货地址只允许一条存活规则''',
  'SELECT ''ACTIVE_SHAPE_KEY 已存在，跳过'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 3) 唯一索引
-- ------------------------------------------------------------
SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ws_delivery_auto_rule'
    AND INDEX_NAME = 'uk_dauto_active_shape');
SET @sql := IF(@idx_exists = 0,
  'ALTER TABLE `ws_delivery_auto_rule` ADD UNIQUE INDEX `uk_dauto_active_shape` (`ACTIVE_SHAPE_KEY`)',
  'SELECT ''uk_dauto_active_shape 已存在，跳过'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------
-- 4) 后置不变式
-- ------------------------------------------------------------
SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ws_delivery_auto_rule'
      AND COLUMN_NAME = 'ACTIVE_SHAPE_KEY') AS col_expect_1,
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ws_delivery_auto_rule'
      AND INDEX_NAME = 'uk_dauto_active_shape') AS idx_expect_1,
  (SELECT COUNT(*) FROM `ws_delivery_auto_rule`
    WHERE `RULE_STATUS` = 3 AND `ACTIVE_SHAPE_KEY` IS NOT NULL) AS cancelled_holding_slot_expect_0;

SELECT '2026-08-11 迁移完成：自动补货规则同款去重键与唯一索引就绪' AS result;

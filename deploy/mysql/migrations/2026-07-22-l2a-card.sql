-- L2-A schema 迁移：ws_card 增加唯一发行锚点 ISSUE_ORDER_ID（决策 L2-A3）
-- 模式与既有迁移一致：所有只读检查在前、任何变更在后；污染即中止且零变更；不使用存储过程。
-- 中止手法：动态 SQL SELECT `<中文原因>` 触发 ER_1054，事务外零残留。

-- ============ 只读检查区（不做任何变更） ============

-- 1) 表必须存在
SET @tbl := (SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name = 'ws_card');
SET @sql := IF(@tbl = 1, 'SELECT 1', 'SELECT `中止：ws_card 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) 列若已存在，必须恰为 bigint 可空（否则视为异构残留，中止）
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ws_card'
               AND column_name = 'ISSUE_ORDER_ID');
SET @colok := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_card'
                 AND column_name = 'ISSUE_ORDER_ID'
                 AND data_type = 'bigint' AND is_nullable = 'YES');
SET @sql := IF(@col = 0 OR @colok = 1, 'SELECT 1',
               'SELECT `中止：ISSUE_ORDER_ID 已存在但类型或可空性不符`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) 同名索引若已存在，必须是无前缀唯一索引（防 L2-DB 踩过的 SUB_PART 假合格）
SET @idx := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'ws_card'
               AND index_name = 'uk_card_issue_order');
SET @idxok := (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ws_card'
                 AND index_name = 'uk_card_issue_order'
                 AND non_unique = 0 AND sub_part IS NULL
                 AND column_name = 'ISSUE_ORDER_ID');
SET @sql := IF(@idx = 0 OR @idxok = 1, 'SELECT 1',
               'SELECT `中止：uk_card_issue_order 已存在但不是合格唯一索引`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 4) 若列已存在，非 NULL 值不得重复（历史数据污染即中止，不静默去重）。
-- 重复检查本身必须走动态 SQL：列不存在时，静态子查询在**语句解析期**就会因
-- Unknown column 报错——IF() 只跳过假分支的求值，跳不过解析。这曾让 fresh 库第一步即炸。
SET @sql := IF(@col = 0, 'SET @dup := 0',
  'SET @dup := (SELECT COUNT(*) FROM (SELECT ISSUE_ORDER_ID FROM ws_card WHERE ISSUE_ORDER_ID IS NOT NULL GROUP BY ISSUE_ORDER_ID HAVING COUNT(*) > 1) d)');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@dup = 0, 'SELECT 1', 'SELECT `中止：ISSUE_ORDER_ID 存在重复值`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 变更区（幂等：已存在即跳过） ============

SET @sql := IF(@col = 0,
  'ALTER TABLE ws_card ADD COLUMN `ISSUE_ORDER_ID` bigint NULL COMMENT ''首次购卡发行订单ID（L2-A3 唯一发行锚点）；L2-A 建卡必写，历史/实体/人工卡为 NULL''',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@idx = 0,
  'ALTER TABLE ws_card ADD UNIQUE INDEX `uk_card_issue_order` (`ISSUE_ORDER_ID`)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 终检：列与唯一索引必须同时就位
SET @final := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_card'
                 AND column_name = 'ISSUE_ORDER_ID' AND data_type = 'bigint')
            + (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ws_card'
                 AND index_name = 'uk_card_issue_order' AND non_unique = 0 AND sub_part IS NULL);
SET @sql := IF(@final = 2, 'SELECT ''L2-A 迁移完成''', 'SELECT `中止：终检失败`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

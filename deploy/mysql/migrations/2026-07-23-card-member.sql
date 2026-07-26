-- CARD-MEMBER schema 迁移（CARD-MEMBER）：ws_card_member 增加授权时间窗 + 唯一成员键；
-- ws_order 增加成员日限额统计组合索引。
-- 模式与既有迁移一致（抄 2026-07-22-l2a-card.sql）：所有只读检查在前、任何变更在后；
-- 污染即中止且零变更；不使用存储过程。
-- 中止手法：动态 SQL SELECT `<中文原因>` 触发 ER_1054，事务外零残留。
-- 唯一键 uk_card_member_user 不含 DATA_STATUS：逻辑删除的授权记录仍占用 (CARD_ID, MEMBER_USER_ID)，
-- 重新授权必须复用原记录，而不是并存第二行（并发重加由该键在库层收敛）。

-- ============ 只读检查区（不做任何变更） ============

-- 1) 两张表必须存在
SET @tbl := (SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name = 'ws_card_member');
SET @sql := IF(@tbl = 1, 'SELECT 1', 'SELECT `中止：ws_card_member 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @otbl := (SELECT COUNT(*) FROM information_schema.tables
              WHERE table_schema = DATABASE() AND table_name = 'ws_order');
SET @sql := IF(@otbl = 1, 'SELECT 1', 'SELECT `中止：ws_order 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) EFFECTIVE_TIME 列若已存在，必须恰为 varchar(14) 可空（否则视为异构残留，中止）
SET @effcol := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ws_card_member'
                  AND column_name = 'EFFECTIVE_TIME');
SET @effok := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_card_member'
                 AND column_name = 'EFFECTIVE_TIME'
                 AND data_type = 'varchar' AND character_maximum_length = 14
                 AND is_nullable = 'YES');
SET @sql := IF(@effcol = 0 OR @effok = 1, 'SELECT 1',
               'SELECT `中止：EFFECTIVE_TIME 已存在但类型或可空性不符`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) EXPIRE_TIME 列同上
SET @expcol := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ws_card_member'
                  AND column_name = 'EXPIRE_TIME');
SET @expok := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_card_member'
                 AND column_name = 'EXPIRE_TIME'
                 AND data_type = 'varchar' AND character_maximum_length = 14
                 AND is_nullable = 'YES');
SET @sql := IF(@expcol = 0 OR @expok = 1, 'SELECT 1',
               'SELECT `中止：EXPIRE_TIME 已存在但类型或可空性不符`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 4) 同名唯一索引若已存在，必须是无前缀、恰好 (CARD_ID, MEMBER_USER_ID) 两列的唯一索引
--    （防 L2-DB 踩过的 SUB_PART 假合格；列序错位同样视为异构残留）
SET @uk := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ws_card_member'
              AND index_name = 'uk_card_member_user');
SET @ukcols := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ws_card_member'
                  AND index_name = 'uk_card_member_user');
SET @ukok := (SELECT COUNT(*) FROM information_schema.statistics
              WHERE table_schema = DATABASE() AND table_name = 'ws_card_member'
                AND index_name = 'uk_card_member_user'
                AND non_unique = 0 AND sub_part IS NULL
                AND ((seq_in_index = 1 AND column_name = 'CARD_ID')
                  OR (seq_in_index = 2 AND column_name = 'MEMBER_USER_ID')));
SET @sql := IF(@uk = 0 OR (@ukcols = 2 AND @ukok = 2), 'SELECT 1',
               'SELECT `中止：uk_card_member_user 已存在但不是合格的 (CARD_ID, MEMBER_USER_ID) 唯一索引`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 5) 迁移前重复检查：同一 (CARD_ID, MEMBER_USER_ID) 存在多行（不分 DATA_STATUS/MEMBER_STATUS）
--    即历史数据污染，SIGNAL 中止、不静默去重——去重会武断决定保留哪条授权与限额。
--    CARD_ID/MEMBER_USER_ID 为建表既有列，静态子查询不会踩「IF() 跳过求值但跳不过解析」的坑
--    （那个坑只在列可能不存在时出现，见 2026-07-22-l2a-card.sql 注释）。
SET @dup := (SELECT COUNT(*) FROM (SELECT CARD_ID, MEMBER_USER_ID FROM ws_card_member
             GROUP BY CARD_ID, MEMBER_USER_ID HAVING COUNT(*) > 1) d);
SET @sql := IF(@dup = 0, 'SELECT 1',
               'SELECT `中止：ws_card_member 存在重复的 CARD_ID+MEMBER_USER_ID，须人工裁决后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 6) ws_order 日限额组合索引若已存在，必须恰为 (CARD_ID, USER_ID, ORDER_TYPE, CREATE_TIME) 四列普通索引
SET @oidx := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
              WHERE table_schema = DATABASE() AND table_name = 'ws_order'
                AND index_name = 'idx_order_card_user_type_time');
SET @oidxcols := (SELECT COUNT(*) FROM information_schema.statistics
                  WHERE table_schema = DATABASE() AND table_name = 'ws_order'
                    AND index_name = 'idx_order_card_user_type_time');
SET @oidxok := (SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ws_order'
                  AND index_name = 'idx_order_card_user_type_time'
                  AND sub_part IS NULL
                  AND ((seq_in_index = 1 AND column_name = 'CARD_ID')
                    OR (seq_in_index = 2 AND column_name = 'USER_ID')
                    OR (seq_in_index = 3 AND column_name = 'ORDER_TYPE')
                    OR (seq_in_index = 4 AND column_name = 'CREATE_TIME')));
SET @sql := IF(@oidx = 0 OR (@oidxcols = 4 AND @oidxok = 4), 'SELECT 1',
               'SELECT `中止：idx_order_card_user_type_time 已存在但列组成不符`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 变更区（幂等：已存在即跳过） ============

-- 列位置与权威结构 02-ws-business.sql 保持一致：DAY_LIMIT_ML 之后、MEMBER_STATUS 之前
SET @sql := IF(@effcol = 0,
  'ALTER TABLE ws_card_member ADD COLUMN `EFFECTIVE_TIME` varchar(14) NULL COMMENT ''授权生效时间，空=立即生效'' AFTER `DAY_LIMIT_ML`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@expcol = 0,
  'ALTER TABLE ws_card_member ADD COLUMN `EXPIRE_TIME` varchar(14) NULL COMMENT ''授权失效时间，空=长期有效'' AFTER `EFFECTIVE_TIME`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@uk = 0,
  'ALTER TABLE ws_card_member ADD UNIQUE INDEX `uk_card_member_user` (`CARD_ID`, `MEMBER_USER_ID`)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@oidx = 0,
  'ALTER TABLE ws_order ADD INDEX `idx_order_card_user_type_time` (`CARD_ID`, `USER_ID`, `ORDER_TYPE`, `CREATE_TIME`)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 终检：两列 + 唯一键两列 + 订单索引四列必须同时就位 ============
SET @final := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_card_member'
                 AND column_name IN ('EFFECTIVE_TIME', 'EXPIRE_TIME')
                 AND data_type = 'varchar' AND character_maximum_length = 14 AND is_nullable = 'YES')
            + (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ws_card_member'
                 AND index_name = 'uk_card_member_user' AND non_unique = 0 AND sub_part IS NULL)
            + (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ws_order'
                 AND index_name = 'idx_order_card_user_type_time' AND sub_part IS NULL);
SET @sql := IF(@final = 8, 'SELECT ''CARD-MEMBER 迁移完成''', 'SELECT `中止：终检失败`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

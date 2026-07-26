-- E2E-03 水配送 包A schema 迁移：
--   ws_delivery_task  补 TASK_NO/STATION_ID/VERSION/计划与实际数量/水费与配送费快照/预约时间/定位状态，
--                     idx_dtask_order 升级为唯一键（一单一任务）+ 任务号唯一键 + 站点状态索引；
--   ws_delivery_appeal 补 ORDER_ID/APPEAL_DESC/RECEIVED_COUNT/COURIER_EVIDENCES + 活动申诉唯一生成列；
--   新表 ws_delivery_exception / ws_delivery_media / ws_delivery_auto_rule / ws_message；
--   字典 1352 补值5，新增 1353/1354/1355/1312/1313，1344 补值7。
-- 模式与既有迁移一致（抄 2026-07-23-card-member.sql）：所有只读检查在前、任何变更在后；
-- 污染即中止且零变更；不使用存储过程。
-- 中止手法：动态 SQL SELECT `<中文原因>` 触发 ER_1054，事务外零残留。
-- 注意「IF() 跳过求值但跳不过解析」坑：凡引用可能不存在列的 SQL 一律走 PREPARE 动态串，
-- 静态子查询只允许触碰建表既有列（见 2026-07-22-l2a-card.sql 注释）。

-- 固定连接字符集，防止调用端沿用 latin1 会话导致中文字典二次编码并被 varchar 上限截断。
SET NAMES utf8mb4;

-- ============ 只读检查区（不做任何变更） ============

-- 1) 四张既有表必须存在
SET @t_task := (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task');
SET @sql := IF(@t_task = 1, 'SELECT 1', 'SELECT `中止：ws_delivery_task 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_appeal := (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal');
SET @sql := IF(@t_appeal = 1, 'SELECT 1', 'SELECT `中止：ws_delivery_appeal 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_order := (SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = 'ws_order');
SET @sql := IF(@t_order = 1, 'SELECT 1', 'SELECT `中止：ws_order 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_flow := (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ws_wallet_flow');
SET @sql := IF(@t_flow = 1, 'SELECT 1', 'SELECT `中止：ws_wallet_flow 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) ws_delivery_task 新列若已存在，逐列核验类型/可空性（异构残留即中止）
--    工具宏：@chk(name, 期望条件计数)。展开写，不用存储过程。
SET @c_taskno := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'TASK_NO');
SET @c_taskno_ok := (SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'TASK_NO'
                       AND data_type = 'varchar' AND character_maximum_length = 32);
SET @sql := IF(@c_taskno = 0 OR @c_taskno_ok = 1, 'SELECT 1', 'SELECT `中止：TASK_NO 已存在但类型不符`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c_station := (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'STATION_ID');
SET @c_station_ok := (SELECT COUNT(*) FROM information_schema.columns
                      WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'STATION_ID'
                        AND data_type = 'bigint');
SET @sql := IF(@c_station = 0 OR @c_station_ok = 1, 'SELECT 1', 'SELECT `中止：STATION_ID 已存在但类型不符`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c_version := (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'VERSION');
SET @c_version_ok := (SELECT COUNT(*) FROM information_schema.columns
                      WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'VERSION'
                        AND data_type = 'int');
SET @sql := IF(@c_version = 0 OR @c_version_ok = 1, 'SELECT 1', 'SELECT `中止：VERSION 已存在但类型不符`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c_prc := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'PLAN_RETURN_COUNT');
SET @c_adc := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'ACTUAL_DELIVERY_COUNT');
SET @c_arc := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'ACTUAL_RETURN_COUNT');
SET @c_wamt := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'WATER_AMOUNT');
SET @c_dfee := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'DELIVERY_FEE');
SET @c_sched := (SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'SCHEDULED_TIME');
SET @c_loc := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task' AND column_name = 'LOCATION_STATUS');

-- 3) ws_delivery_appeal 新列存在性
SET @a_order := (SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal' AND column_name = 'ORDER_ID');
SET @a_desc := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal' AND column_name = 'APPEAL_DESC');
SET @a_recv := (SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal' AND column_name = 'RECEIVED_COUNT');
SET @a_cev := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal' AND column_name = 'COURIER_EVIDENCES');
SET @a_active := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal' AND column_name = 'ACTIVE_TASK_KEY');

-- 4) APPEAL_REASON 目标类型 varchar(20)（原 varchar(500)）：已是 20 或仍是 500 都合法，其他异构中止；
--    改窄前必须确认无超长数据（静态列，安全）
SET @a_reason_len := (SELECT character_maximum_length FROM information_schema.columns
                      WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal' AND column_name = 'APPEAL_REASON');
SET @sql := IF(@a_reason_len IN (20, 500), 'SELECT 1', 'SELECT `中止：APPEAL_REASON 类型异构`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @a_reason_overlong := (SELECT COUNT(*) FROM ws_delivery_appeal WHERE CHAR_LENGTH(APPEAL_REASON) > 20);
SET @sql := IF(@a_reason_len = 20 OR @a_reason_overlong = 0, 'SELECT 1',
               'SELECT `中止：存在超过20字符的 APPEAL_REASON 历史值，须人工归一化原因码后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 5) 唯一键升级前的数据污染检查（静态列，安全）：
--    同一 ORDER_ID 多条任务 = 违背「一单一任务」，人工裁决，不静默去重
SET @dup_order := (SELECT COUNT(*) FROM (SELECT ORDER_ID FROM ws_delivery_task
                   GROUP BY ORDER_ID HAVING COUNT(*) > 1) d);
SET @sql := IF(@dup_order = 0, 'SELECT 1',
               'SELECT `中止：ws_delivery_task 存在同一 ORDER_ID 多条任务，须人工裁决后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

--    同一任务多条活动申诉（APPEAL_STATUS=1）= 生成列唯一键装不上，人工裁决
SET @dup_active := (SELECT COUNT(*) FROM (SELECT TASK_ID FROM ws_delivery_appeal
                    WHERE APPEAL_STATUS = 1 GROUP BY TASK_ID HAVING COUNT(*) > 1) d);
SET @sql := IF(@dup_active = 0, 'SELECT 1',
               'SELECT `中止：同一任务存在多条待处理申诉，须人工裁决后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

--    TASK_NO 列已存在时不得有重复/空值（动态串：列可能不存在）
SET @sql := IF(@c_taskno = 0, 'SELECT 1',
  'SET @dup_taskno := (SELECT COUNT(*) FROM (SELECT TASK_NO FROM ws_delivery_task GROUP BY TASK_NO HAVING COUNT(*) > 1) d) + (SELECT COUNT(*) FROM ws_delivery_task WHERE TASK_NO IS NULL OR TASK_NO = '''')');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@c_taskno = 0 OR IFNULL(@dup_taskno, 0) = 0, 'SELECT 1',
               'SELECT `中止：既有 TASK_NO 存在重复或空值，须人工裁决后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 6) STATION_ID 回填可行性：每条任务的关联订单必须存在且带 STATION_ID（缺失即中止，不造数）
SET @orphan_station := (SELECT COUNT(*) FROM ws_delivery_task t
                        LEFT JOIN ws_order o ON o.ID = t.ORDER_ID
                        WHERE o.ID IS NULL OR o.STATION_ID IS NULL);
SET @sql := IF(@c_station = 1 OR @orphan_station = 0, 'SELECT 1',
               'SELECT `中止：存在无法回填 STATION_ID 的任务（订单缺失或订单无水站），须人工裁决后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 7) 申诉 ORDER_ID 回填可行性：申诉的任务必须存在
SET @orphan_appeal := (SELECT COUNT(*) FROM ws_delivery_appeal a
                       LEFT JOIN ws_delivery_task t ON t.ID = a.TASK_ID
                       WHERE t.ID IS NULL);
SET @sql := IF(@a_order = 1 OR @orphan_appeal = 0, 'SELECT 1',
               'SELECT `中止：存在关联任务缺失的申诉，无法回填 ORDER_ID，须人工裁决后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 8) 索引现状
SET @i_uk_order := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task'
                      AND index_name = 'uk_dtask_order');
SET @i_old_order := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task'
                       AND index_name = 'idx_dtask_order');
SET @i_uk_taskno := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task'
                       AND index_name = 'uk_dtask_task_no');
SET @i_station := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task'
                     AND index_name = 'idx_dtask_station_status');
SET @i_uk_active := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal'
                       AND index_name = 'uk_appeal_active_task');
SET @i_appeal_order := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal'
                          AND index_name = 'idx_appeal_order');

-- 9) 新表存在性（已存在时轻校验签名列，防异构同名残留）
SET @t_exc := (SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_exception');
SET @t_exc_sig := (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_exception'
                     AND column_name IN ('TASK_ID', 'COURIER_ID', 'EXCEPTION_REASON', 'EXCEPTION_DESC'));
SET @sql := IF(@t_exc = 0 OR @t_exc_sig = 4, 'SELECT 1', 'SELECT `中止：ws_delivery_exception 同名异构残留`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_media := (SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_media');
SET @t_media_sig := (SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_media'
                       AND column_name IN ('MEDIA_KEY', 'OWNER_USER_ID', 'MEDIA_PURPOSE', 'CONTENT_SHA256'));
SET @sql := IF(@t_media = 0 OR @t_media_sig = 4, 'SELECT 1', 'SELECT `中止：ws_delivery_media 同名异构残留`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_auto := (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_auto_rule');
SET @t_auto_sig := (SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_auto_rule'
                      AND column_name IN ('RULE_KEY', 'USER_ID', 'INTERVAL_DAYS', 'ANCHOR_TIME'));
SET @sql := IF(@t_auto = 0 OR @t_auto_sig = 4, 'SELECT 1', 'SELECT `中止：ws_delivery_auto_rule 同名异构残留`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_msg := (SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'ws_message');
SET @t_msg_sig := (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'ws_message'
                     AND column_name IN ('USER_ID', 'MSG_DOMAIN', 'MSG_TITLE', 'SEND_STATUS'));
SET @sql := IF(@t_msg = 0 OR @t_msg_sig = 4, 'SELECT 1', 'SELECT `中止：ws_message 同名异构残留`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 10) 基础字典表只有自增主键；目标业务键存在重复时必须先人工裁决，不能依赖 INSERT IGNORE。
SET @dup_dict_type := (
  SELECT COUNT(*) FROM (
    SELECT DICT_TYPE FROM api_dict_type
    WHERE DICT_TYPE IN ('1312', '1313', '1353', '1354', '1355')
    GROUP BY DICT_TYPE HAVING COUNT(*) > 1
  ) d
);
SET @sql := IF(@dup_dict_type = 0, 'SELECT 1',
               'SELECT `中止：目标 api_dict_type 业务键重复，须人工裁决后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @dup_dict_data := (
  SELECT COUNT(*) FROM (
    SELECT DICT_TYPE, DICT_VALUE FROM api_dict_data
    WHERE (DICT_TYPE = '1344' AND DICT_VALUE = 7)
       OR (DICT_TYPE = '1352' AND DICT_VALUE = 5)
       OR DICT_TYPE IN ('1312', '1313', '1353', '1354', '1355')
    GROUP BY DICT_TYPE, DICT_VALUE HAVING COUNT(*) > 1
  ) d
);
SET @sql := IF(@dup_dict_data = 0, 'SELECT 1',
               'SELECT `中止：目标 api_dict_data 业务键重复，须人工裁决后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 变更区（幂等：已存在即跳过；NOT NULL 列先加可空、回填、终检后再收紧） ============

-- ws_delivery_task 新列（列位置与权威结构 02-ws-business.sql 一致）
SET @sql := IF(@c_taskno = 0,
  'ALTER TABLE ws_delivery_task ADD COLUMN `TASK_NO` varchar(32) NULL COMMENT ''任务号(max32)：DT+sha256(orderNo)前30位大写十六进制，由订单号确定性派生（禁日期禁序列，重放恒定）'' AFTER `UPDATE_TIME`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@c_station = 0,
  'ALTER TABLE ws_delivery_task ADD COLUMN `STATION_ID` bigint NULL COMMENT ''配送水站ID快照（ws_station.ID）；配送员服务范围判定只吃本列，不回查订单（E2E-03 规则8）'' AFTER `USER_ID`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@c_prc = 0,
  'ALTER TABLE ws_delivery_task ADD COLUMN `PLAN_RETURN_COUNT` int NULL COMMENT ''计划回收空桶数量'' AFTER `DELIVERY_COUNT`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@c_adc = 0,
  'ALTER TABLE ws_delivery_task ADD COLUMN `ACTUAL_DELIVERY_COUNT` int NULL COMMENT ''实际配送数量（签收时结构化落库，签收前为空）'' AFTER `PLAN_RETURN_COUNT`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@c_arc = 0,
  'ALTER TABLE ws_delivery_task ADD COLUMN `ACTUAL_RETURN_COUNT` int NULL COMMENT ''实际回收数量（签收时结构化落库，签收前为空）'' AFTER `ACTUAL_DELIVERY_COUNT`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@c_wamt = 0,
  'ALTER TABLE ws_delivery_task ADD COLUMN `WATER_AMOUNT` bigint NULL COMMENT ''水费快照(分)，下单时冻结'' AFTER `ACTUAL_RETURN_COUNT`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@c_dfee = 0,
  'ALTER TABLE ws_delivery_task ADD COLUMN `DELIVERY_FEE` bigint NULL COMMENT ''配送费快照(分)，下单时冻结；ws_order.ORDER_AMOUNT=WATER_AMOUNT+DELIVERY_FEE（E2E-03 规则2）'' AFTER `WATER_AMOUNT`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@c_version = 0,
  'ALTER TABLE ws_delivery_task ADD COLUMN `VERSION` int NULL COMMENT ''乐观锁版本：创建=1，每次状态转换+1；所有转换必须 WHERE VERSION=期望值（E2E-03 规则11）'' AFTER `TASK_STATUS`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@c_sched = 0,
  'ALTER TABLE ws_delivery_task ADD COLUMN `SCHEDULED_TIME` varchar(14) NULL COMMENT ''预约配送时间；空=即时单付完即入池，非空=到点(<=now)才进入可接列表（E2E-03 规则19，查询时间语义，不建调度器）'' AFTER `VERSION`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@c_loc = 0,
  'ALTER TABLE ws_delivery_task ADD COLUMN `LOCATION_STATUS` tinyint NULL COMMENT ''签收定位记录状态(1353)：1定位已记录 2定位未记录；签收前为空。声明1时三照必须携带合法坐标，禁止文案超出实际证据'' AFTER `SIGN_PHOTOS`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 回填（动态串：新列在解析期已存在）
-- TASK_NO：历史行确定性回填 DT-LEGACY-<ID>（新单一律走派生算法；仅历史行用 ID 兜底，保证唯一且可追溯）
SET @sql := IF(@c_taskno = 0,
  'UPDATE ws_delivery_task SET TASK_NO = CONCAT(''DT-LEGACY-'', ID) WHERE TASK_NO IS NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- STATION_ID：从关联订单回填（检查区已保证全部可回填）
SET @sql := IF(@c_station = 0,
  'UPDATE ws_delivery_task t JOIN ws_order o ON o.ID = t.ORDER_ID SET t.STATION_ID = o.STATION_ID WHERE t.STATION_ID IS NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- PLAN_RETURN_COUNT：历史行无记录按 0（无回收计划）；VERSION：历史行统一从 1 起（乐观锁基线，不重构历史转换数）
SET @sql := IF(@c_prc = 0, 'UPDATE ws_delivery_task SET PLAN_RETURN_COUNT = 0 WHERE PLAN_RETURN_COUNT IS NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@c_version = 0, 'UPDATE ws_delivery_task SET VERSION = 1 WHERE VERSION IS NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 水费/配送费快照：历史行无拆分依据，按「水费=订单总额、配送费=0」落地并保持总额恒等式；
-- 不逆推虚构拆分（拆分快照只对新单具有权威性）
SET @sql := IF(@c_wamt = 0,
  'UPDATE ws_delivery_task t JOIN ws_order o ON o.ID = t.ORDER_ID SET t.WATER_AMOUNT = o.ORDER_AMOUNT WHERE t.WATER_AMOUNT IS NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@c_dfee = 0, 'UPDATE ws_delivery_task SET DELIVERY_FEE = 0 WHERE DELIVERY_FEE IS NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 回填后校验：不允许残留 NULL（有残留说明存在孤儿任务，中止人工裁决）
SET @sql := 'SET @task_nulls := (SELECT COUNT(*) FROM ws_delivery_task WHERE TASK_NO IS NULL OR STATION_ID IS NULL OR PLAN_RETURN_COUNT IS NULL OR VERSION IS NULL OR WATER_AMOUNT IS NULL OR DELIVERY_FEE IS NULL)';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@task_nulls = 0, 'SELECT 1', 'SELECT `中止：ws_delivery_task 回填后仍有空值，须人工裁决后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 收紧 NOT NULL（幂等：无论本次是否新加，统一 MODIFY 到目标定义）
SET @sql := 'ALTER TABLE ws_delivery_task
  MODIFY COLUMN `TASK_NO` varchar(32) NOT NULL COMMENT ''任务号(max32)：DT+sha256(orderNo)前30位大写十六进制，由订单号确定性派生（禁日期禁序列，重放恒定）'',
  MODIFY COLUMN `STATION_ID` bigint NOT NULL COMMENT ''配送水站ID快照（ws_station.ID）；配送员服务范围判定只吃本列，不回查订单（E2E-03 规则8）'',
  MODIFY COLUMN `PLAN_RETURN_COUNT` int NOT NULL COMMENT ''计划回收空桶数量'',
  MODIFY COLUMN `WATER_AMOUNT` bigint NOT NULL COMMENT ''水费快照(分)，下单时冻结'',
  MODIFY COLUMN `DELIVERY_FEE` bigint NOT NULL COMMENT ''配送费快照(分)，下单时冻结；ws_order.ORDER_AMOUNT=WATER_AMOUNT+DELIVERY_FEE（E2E-03 规则2）'',
  MODIFY COLUMN `VERSION` int NOT NULL COMMENT ''乐观锁版本：创建=1，每次状态转换+1；所有转换必须 WHERE VERSION=期望值（E2E-03 规则11）''';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 索引升级：先建两个唯一键，再撤旧普通索引（顺序保证任一时刻 ORDER_ID 都有索引可用）
SET @sql := IF(@i_uk_taskno = 0,
  'ALTER TABLE ws_delivery_task ADD UNIQUE INDEX `uk_dtask_task_no` (`TASK_NO`)', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@i_uk_order = 0,
  'ALTER TABLE ws_delivery_task ADD UNIQUE INDEX `uk_dtask_order` (`ORDER_ID`)', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@i_old_order = 1,
  'ALTER TABLE ws_delivery_task DROP INDEX `idx_dtask_order`', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@i_station = 0,
  'ALTER TABLE ws_delivery_task ADD INDEX `idx_dtask_station_status` (`STATION_ID`, `TASK_STATUS`)', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ws_delivery_appeal 新列
SET @sql := IF(@a_order = 0,
  'ALTER TABLE ws_delivery_appeal ADD COLUMN `ORDER_ID` bigint NULL COMMENT ''配送订单ID（共键之一：必须等于任务的 ORDER_ID，错位 fail-closed）'' AFTER `TASK_ID`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@a_desc = 0,
  'ALTER TABLE ws_delivery_appeal ADD COLUMN `APPEAL_DESC` varchar(500) NULL COMMENT ''申诉说明(max500)；服务端创建时必填校验，历史行可空'' AFTER `APPEAL_REASON`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@a_recv = 0,
  'ALTER TABLE ws_delivery_appeal ADD COLUMN `RECEIVED_COUNT` int NULL COMMENT ''用户实收数量（结构化数字）；服务端创建时必填校验，历史行可空'' AFTER `APPEAL_DESC`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@a_cev = 0,
  'ALTER TABLE ws_delivery_appeal ADD COLUMN `COURIER_EVIDENCES` text NULL COMMENT ''配送员举证JSON数组，元素{description, evidenceRefs:[mediaKey], time}；只允许任务归属配送员在申诉中追加（E2E-03 规则16）'' AFTER `APPEAL_PHOTOS`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 申诉 ORDER_ID 回填（检查区已保证任务都在）并收紧 NOT NULL
SET @sql := IF(@a_order = 0,
  'UPDATE ws_delivery_appeal a JOIN ws_delivery_task t ON t.ID = a.TASK_ID SET a.ORDER_ID = t.ORDER_ID WHERE a.ORDER_ID IS NULL',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := 'SET @appeal_nulls := (SELECT COUNT(*) FROM ws_delivery_appeal WHERE ORDER_ID IS NULL)';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@appeal_nulls = 0, 'SELECT 1', 'SELECT `中止：ws_delivery_appeal.ORDER_ID 回填后仍有空值`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := 'ALTER TABLE ws_delivery_appeal MODIFY COLUMN `ORDER_ID` bigint NOT NULL COMMENT ''配送订单ID（共键之一：必须等于任务的 ORDER_ID，错位 fail-closed）''';
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- APPEAL_REASON 收窄为原因码（检查区已保证无超长值）
SET @sql := IF(@a_reason_len = 20, 'SELECT 1',
  'ALTER TABLE ws_delivery_appeal MODIFY COLUMN `APPEAL_REASON` varchar(20) NOT NULL COMMENT ''申诉原因码(max20)：QUANTITY数量不符/QUALITY质量问题/DAMAGE货损/PLACEMENT摆放不符/OTHER其他（对齐小程序契约枚举）''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 活动申诉唯一：生成列 + 唯一键（检查区已保证无并存活动申诉）
SET @sql := IF(@a_active = 0,
  'ALTER TABLE ws_delivery_appeal ADD COLUMN `ACTIVE_TASK_KEY` bigint GENERATED ALWAYS AS (IF(`APPEAL_STATUS` = 1, `TASK_ID`, NULL)) STORED COMMENT ''活动申诉唯一占位（生成列）'' AFTER `HANDLE_RESULT`',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@i_uk_active = 0,
  'ALTER TABLE ws_delivery_appeal ADD UNIQUE INDEX `uk_appeal_active_task` (`ACTIVE_TASK_KEY`)', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@i_appeal_order = 0,
  'ALTER TABLE ws_delivery_appeal ADD INDEX `idx_appeal_order` (`ORDER_ID`)', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 新表（幂等：仅缺失时创建；结构与 02-ws-business.sql 权威定义一致）
SET @sql := IF(@t_exc = 1, 'SELECT 1',
'CREATE TABLE `ws_delivery_exception` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT ''主键'',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT ''逻辑删除：0正常 1删除'',
  `CREATE_BY`        bigint       NOT NULL COMMENT ''创建人ID'',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT ''创建时间（异常动作时间，不早于任务最后已发生节点）'',
  `UPDATE_BY`        bigint       NOT NULL COMMENT ''更新人ID'',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT ''更新时间'',
  `TASK_ID`          bigint       NOT NULL COMMENT ''配送任务ID'',
  `COURIER_ID`       bigint       NOT NULL COMMENT ''上报配送员ID（ws_courier.ID，必须等于任务归属配送员）'',
  `EXCEPTION_REASON` tinyint      NOT NULL COMMENT ''异常原因(1354)：1联系不上用户 2地址异常 3数量问题 4货物破损 5其他'',
  `EXCEPTION_DESC`   varchar(500) NOT NULL COMMENT ''异常说明(max500)'',
  `EVIDENCE_REFS`    text         COMMENT ''举证JSON数组（受控媒体键 mediaKey）'',
  PRIMARY KEY (`ID`),
  INDEX `idx_dexc_task` (`TASK_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT=''配送异常记录表''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@t_media = 1, 'SELECT 1',
'CREATE TABLE `ws_delivery_media` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT ''主键'',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT ''逻辑删除：0正常 1删除'',
  `CREATE_BY`        bigint       NOT NULL COMMENT ''创建人ID'',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT ''创建时间'',
  `UPDATE_BY`        bigint       NOT NULL COMMENT ''更新人ID'',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT ''更新时间'',
  `MEDIA_KEY`        varchar(64)  NOT NULL COMMENT ''受控媒体键(max64)：DM+sha256(ownerUserId:contentSha256:purpose)前30位大写；引用方只存本键'',
  `OWNER_USER_ID`    bigint       NOT NULL COMMENT ''登记人用户ID（ws_user.ID）；举证/三照只允许本人登记的媒体，防挪用他人证据'',
  `MEDIA_PURPOSE`    tinyint      NOT NULL COMMENT ''用途：1签收三照 2申诉举证 3异常举证（跨用途引用一律拒绝）'',
  `CONTENT_SHA256`   char(64)     NOT NULL COMMENT ''内容SHA-256（完整性校验元数据；本地适配器落盘后校验）'',
  `SIZE_BYTES`       bigint       NOT NULL COMMENT ''内容字节数'',
  `MIME_TYPE`        varchar(50)  NOT NULL COMMENT ''MIME类型(max50)，仅允许 image/*'',
  `BOUND_TASK_ID`    bigint       COMMENT ''已绑定任务ID；签收/举证提交时原子占用（WHERE BOUND_TASK_ID IS NULL），防同一照片跨任务复用'',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_dmedia_key` (`MEDIA_KEY`),
  INDEX `idx_dmedia_owner` (`OWNER_USER_ID`),
  INDEX `idx_dmedia_task` (`BOUND_TASK_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT=''配送受控媒体表''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@t_auto = 1, 'SELECT 1',
'CREATE TABLE `ws_delivery_auto_rule` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT ''主键'',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT ''逻辑删除：0正常 1删除'',
  `CREATE_BY`        bigint       NOT NULL COMMENT ''创建人ID'',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT ''创建时间'',
  `UPDATE_BY`        bigint       NOT NULL COMMENT ''更新人ID'',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT ''更新时间'',
  `RULE_KEY`         varchar(64)  NOT NULL COMMENT ''规则创建幂等键(max64)：sha256(userId:requestId)，重复提交命中唯一键不重复建规则'',
  `USER_ID`          bigint       NOT NULL COMMENT ''规则所属用户ID（ws_user.ID）'',
  `CARD_ID`          bigint       NOT NULL COMMENT ''扣款水卡ID（ws_card.ID，逐期创单时按当期状态重新校验）'',
  `STATION_ID`       bigint       NOT NULL COMMENT ''配送水站ID'',
  `WATER_TYPE_ID`    bigint       NOT NULL COMMENT ''水种ID（ws_water_type.ID）'',
  `CONTAINER_SPEC`   varchar(20)  NOT NULL COMMENT ''容器规格(max20)'',
  `DELIVERY_COUNT`   int          NOT NULL COMMENT ''每期配送数量'',
  `PLAN_RETURN_COUNT` int         NOT NULL COMMENT ''每期计划回收数量'',
  `RECEIVE_ADDRESS`  varchar(200) NOT NULL COMMENT ''收水地址(max200)'',
  `RECEIVE_PHONE`    varchar(20)  NOT NULL COMMENT ''收货电话(max20)'',
  `INTERVAL_DAYS`    int          NOT NULL COMMENT ''固定周期天数(3~90，用户显式配置)'',
  `ANCHOR_TIME`      varchar(14)  NOT NULL COMMENT ''周期锚点=规则创建时间；第n期到期时间=锚点+n*INTERVAL_DAYS天，期序是幂等键组成部分'',
  `RULE_STATUS`      tinyint      NOT NULL COMMENT ''规则状态(1355)：1启用 2停用'',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_dauto_rule_key` (`RULE_KEY`),
  INDEX `idx_dauto_user` (`USER_ID`),
  INDEX `idx_dauto_status` (`RULE_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT=''自动补货规则表''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@t_msg = 1, 'SELECT 1',
'CREATE TABLE `ws_message` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT ''主键'',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT ''逻辑删除：0正常 1删除'',
  `CREATE_BY`        bigint       NOT NULL COMMENT ''创建人ID'',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT ''创建时间'',
  `UPDATE_BY`        bigint       NOT NULL COMMENT ''更新人ID'',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT ''更新时间'',
  `USER_ID`          bigint       NOT NULL COMMENT ''收件用户ID（ws_user.ID）；查询必须按会话用户强制过滤（铁律6）'',
  `MSG_DOMAIN`       tinyint      NOT NULL COMMENT ''消息领域(1312)：1取水 2卡券 3配送 4机主 5系统'',
  `MSG_TITLE`        varchar(100) NOT NULL COMMENT ''标题(max100)'',
  `MSG_CONTENT`      varchar(500) NOT NULL COMMENT ''正文(max500)，不得包含明文手机号等敏感信息'',
  `MSG_CHANNEL`      tinyint      NOT NULL COMMENT ''渠道：1站内（一期固定；微信订阅消息不在本期范围）'',
  `SEND_STATUS`      tinyint      NOT NULL COMMENT ''发送状态(1313)：1待发送 2发送中 3发送失败 4已送达（站内消息落库即送达=4）'',
  `SEND_TIME`        varchar(14)  COMMENT ''发送时间（与触发它的业务动作时间同源）'',
  `READ_FLAG`        tinyint      NOT NULL DEFAULT 0 COMMENT ''已读：0未读 1已读'',
  `OBJECT_TYPE`      varchar(20)  COMMENT ''关联对象类型(max20)：order/task/appeal/device/service'',
  `OBJECT_ID`        varchar(64)  COMMENT ''关联对象业务键(max64)：订单号/任务号/申诉ID'',
  PRIMARY KEY (`ID`),
  INDEX `idx_message_user_time` (`USER_ID`, `SEND_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT=''站内消息表''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 字典按业务键修复已有值、补齐缺失值；重复键已在只读检查区 fail-closed。
UPDATE api_dict_type t
JOIN (
  SELECT '1353' DICT_TYPE, '签收定位状态' DICT_NAME, '配送三照签收定位记录状态' DICT_REMARK
  UNION ALL SELECT '1354', '配送异常原因', '配送履约异常上报原因'
  UNION ALL SELECT '1355', '自动补货规则状态', '配送自动补货规则启停'
  UNION ALL SELECT '1312', '站内消息领域', '站内消息业务领域'
  UNION ALL SELECT '1313', '站内消息发送状态', '站内消息发送状态'
) s ON s.DICT_TYPE = t.DICT_TYPE
SET t.DICT_NAME = s.DICT_NAME, t.DICT_REMARK = s.DICT_REMARK;

INSERT INTO api_dict_type(DICT_NAME, DICT_TYPE, DICT_REMARK)
SELECT s.DICT_NAME, s.DICT_TYPE, s.DICT_REMARK
FROM (
  SELECT '1353' DICT_TYPE, '签收定位状态' DICT_NAME, '配送三照签收定位记录状态' DICT_REMARK
  UNION ALL SELECT '1354', '配送异常原因', '配送履约异常上报原因'
  UNION ALL SELECT '1355', '自动补货规则状态', '配送自动补货规则启停'
  UNION ALL SELECT '1312', '站内消息领域', '站内消息业务领域'
  UNION ALL SELECT '1313', '站内消息发送状态', '站内消息发送状态'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

UPDATE api_dict_data d
JOIN (
  SELECT '1352' DICT_TYPE, 5 DICT_VALUE, 5 DICT_SORT, '补送待执行' DICT_LABEL
  UNION ALL SELECT '1344', 7, 7, '配送扣减'
  UNION ALL SELECT '1353', 1, 1, '定位已记录'
  UNION ALL SELECT '1353', 2, 2, '定位未记录'
  UNION ALL SELECT '1354', 1, 1, '联系不上用户'
  UNION ALL SELECT '1354', 2, 2, '地址异常'
  UNION ALL SELECT '1354', 3, 3, '数量问题'
  UNION ALL SELECT '1354', 4, 4, '货物破损'
  UNION ALL SELECT '1354', 5, 5, '其他'
  UNION ALL SELECT '1355', 1, 1, '启用'
  UNION ALL SELECT '1355', 2, 2, '停用'
  UNION ALL SELECT '1312', 1, 1, '取水'
  UNION ALL SELECT '1312', 2, 2, '卡券'
  UNION ALL SELECT '1312', 3, 3, '配送'
  UNION ALL SELECT '1312', 4, 4, '机主'
  UNION ALL SELECT '1312', 5, 5, '系统'
  UNION ALL SELECT '1313', 1, 1, '待发送'
  UNION ALL SELECT '1313', 2, 2, '发送中'
  UNION ALL SELECT '1313', 3, 3, '发送失败'
  UNION ALL SELECT '1313', 4, 4, '已送达'
) s ON s.DICT_TYPE = d.DICT_TYPE AND s.DICT_VALUE = d.DICT_VALUE
SET d.DICT_CLASS = NULL, d.DICT_DEFAULT_FLAG = 1, d.DICT_SORT = s.DICT_SORT, d.DICT_LABEL = s.DICT_LABEL;

INSERT INTO api_dict_data(DICT_CLASS, DICT_DEFAULT_FLAG, DICT_TYPE, DICT_SORT, DICT_VALUE, DICT_LABEL)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL
FROM (
  SELECT '1352' DICT_TYPE, 5 DICT_VALUE, 5 DICT_SORT, '补送待执行' DICT_LABEL
  UNION ALL SELECT '1344', 7, 7, '配送扣减'
  UNION ALL SELECT '1353', 1, 1, '定位已记录'
  UNION ALL SELECT '1353', 2, 2, '定位未记录'
  UNION ALL SELECT '1354', 1, 1, '联系不上用户'
  UNION ALL SELECT '1354', 2, 2, '地址异常'
  UNION ALL SELECT '1354', 3, 3, '数量问题'
  UNION ALL SELECT '1354', 4, 4, '货物破损'
  UNION ALL SELECT '1354', 5, 5, '其他'
  UNION ALL SELECT '1355', 1, 1, '启用'
  UNION ALL SELECT '1355', 2, 2, '停用'
  UNION ALL SELECT '1312', 1, 1, '取水'
  UNION ALL SELECT '1312', 2, 2, '卡券'
  UNION ALL SELECT '1312', 3, 3, '配送'
  UNION ALL SELECT '1312', 4, 4, '机主'
  UNION ALL SELECT '1312', 5, 5, '系统'
  UNION ALL SELECT '1313', 1, 1, '待发送'
  UNION ALL SELECT '1313', 2, 2, '发送中'
  UNION ALL SELECT '1313', 3, 3, '发送失败'
  UNION ALL SELECT '1313', 4, 4, '已送达'
) s
WHERE NOT EXISTS (
  SELECT 1 FROM api_dict_data d
  WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE
);

-- ============ 终检：关键列/唯一键/新表必须同时就位 ============
SET @final :=
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task'
       AND column_name IN ('TASK_NO', 'STATION_ID', 'VERSION', 'PLAN_RETURN_COUNT', 'ACTUAL_DELIVERY_COUNT',
                           'ACTUAL_RETURN_COUNT', 'WATER_AMOUNT', 'DELIVERY_FEE', 'SCHEDULED_TIME', 'LOCATION_STATUS'))
  + (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_task'
       AND index_name IN ('uk_dtask_order', 'uk_dtask_task_no', 'idx_dtask_station_status') AND sub_part IS NULL)
  + (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal'
       AND column_name IN ('ORDER_ID', 'APPEAL_DESC', 'RECEIVED_COUNT', 'COURIER_EVIDENCES', 'ACTIVE_TASK_KEY'))
  + (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal'
       AND index_name = 'uk_appeal_active_task' AND non_unique = 0)
  + (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema = DATABASE()
       AND table_name IN ('ws_delivery_exception', 'ws_delivery_media', 'ws_delivery_auto_rule', 'ws_message'));
SET @sql := IF(@final = 23, 'SELECT ''E2E-03 配送域迁移完成''', 'SELECT `中止：终检失败`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

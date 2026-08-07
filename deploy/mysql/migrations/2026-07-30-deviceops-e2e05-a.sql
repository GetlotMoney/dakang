-- E2E-05 设备运营与运维 包A 迁移：告警幂等键、工单六状态模型、批量指令、SIM 运维字段、媒体门户。
--
-- 【本迁移动什么】
-- 1. ws_alarm      + ACTIVE_DEDUPE_KEY（可空唯一）+ HANDLE_BY/HANDLE_TIME，并为存量活动告警回填活动键；
-- 2. ws_work_order + WORK_TYPE/APPLICANT_USER_ID/REQUEST_ID/RESULT_PHOTOS/REVIEW_*/REJECT_REASON/CLOSE_TIME/VERSION，
--                   ORDER_NO 唯一化、ALARM_ID/REQUEST_ID 可空唯一，状态值按旧四状态→新六状态映射；
-- 3. ws_command    + BATCH_ID + uk_cmd_batch_device（批次内设备唯一）；
-- 4. 新表 ws_command_batch；
-- 5. ws_device     + SIM_STATUS/SIM_EXPIRE_TIME/LAST_STATUS_DEVICE_TIME；
-- 6. ws_delivery_media + OWNER_PORTAL（员工与用户身份空间隔离）；
-- 7. 字典：1362 重写为六状态、新增 1365/1366/1367/1368、1320 补 8价格同步、1363 补 10告警状态变化；
-- 8. 菜单：二级 1024/1025/1026 + 功能点 1142/1143/1144，并绑定超管。
--
-- 【工单状态映射（旧1362四状态 → 新1362六状态）】
--   旧1待派工 → 新2待分配；旧2处理中 → 新3处理中；旧3已完成 → 新4待复核（完成但未质检，
--   新流程要求复核关闭）；旧4已关闭 → 新5已关闭。新1待确认/新6已驳回 无旧值来源。
--   映射顺序刻意从大值往小值执行，避免「旧2→新3」再被「旧3→新4」二次搬运。
--
-- 模式与 E2E-04 各包一致：只读检查在前、任何变更在后、污染即中止且零变更；
-- 中止手法为动态 SQL SELECT `<中文原因>` 触发 ER_1054，事务外零残留。不使用存储过程。

SET NAMES utf8mb4;

-- ============ 只读检查区（不做任何变更） ============

SET @t_alarm := (SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = 'ws_alarm');
SET @sql := IF(@t_alarm = 1, 'SELECT 1', 'SELECT `中止：ws_alarm 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_wo := (SELECT COUNT(*) FROM information_schema.tables
              WHERE table_schema = DATABASE() AND table_name = 'ws_work_order');
SET @sql := IF(@t_wo = 1, 'SELECT 1', 'SELECT `中止：ws_work_order 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_cmd := (SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'ws_command');
SET @sql := IF(@t_cmd = 1, 'SELECT 1', 'SELECT `中止：ws_command 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 幂等断点识别：ACTIVE_DEDUPE_KEY 已在 = 本迁移执行过，各 ALTER 需逐项跳过
SET @done_alarm := (SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'ws_alarm'
                      AND column_name = 'ACTIVE_DEDUPE_KEY');

-- ORDER_NO 唯一化前置：存量重复即中止交人工（不能静默删数据）
SET @wo_dup := (SELECT COUNT(*) FROM (
    SELECT ORDER_NO FROM ws_work_order GROUP BY ORDER_NO HAVING COUNT(*) > 1) x);
SET @sql := IF(@wo_dup = 0, 'SELECT 1', 'SELECT `中止：ws_work_order.ORDER_NO 存在重复值，唯一化前请先人工去重`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ALARM_ID 可空唯一前置：同一告警已转多个工单即中止
SET @wo_alarm_dup := (SELECT COUNT(*) FROM (
    SELECT ALARM_ID FROM ws_work_order WHERE ALARM_ID IS NOT NULL
    GROUP BY ALARM_ID HAVING COUNT(*) > 1) x);
SET @sql := IF(@wo_alarm_dup = 0, 'SELECT 1', 'SELECT `中止：存在同一告警转出多个工单的数据，请先人工收敛`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 活动告警回填前置：同设备+类型+来源已有多条活动告警即中止（回填唯一键会撞）
SET @alarm_active_dup := (SELECT COUNT(*) FROM (
    SELECT DEVICE_ID, ALARM_TYPE, IFNULL(SOURCE_REF, '') AS SRC
    FROM ws_alarm WHERE ALARM_STATUS IN (1, 2) AND DATA_STATUS = 0
    GROUP BY DEVICE_ID, ALARM_TYPE, IFNULL(SOURCE_REF, '') HAVING COUNT(*) > 1) x);
SET @sql := IF(@alarm_active_dup = 0, 'SELECT 1',
               'SELECT `中止：存在同设备同类型同来源的多条活动告警（先COUNT再INSERT的历史穿透），请先人工收敛`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 字典污染阻断：目标字典项重复即中止
SET @d_dup := (SELECT COUNT(*) FROM (
    SELECT DICT_TYPE, DICT_VALUE FROM api_dict_data
    WHERE DICT_TYPE IN ('1362', '1365', '1366', '1367', '1368')
       OR (DICT_TYPE = '1320' AND DICT_VALUE = 8)
       OR (DICT_TYPE = '1363' AND DICT_VALUE = 10)
    GROUP BY DICT_TYPE, DICT_VALUE HAVING COUNT(*) > 1) x);
SET @sql := IF(@d_dup = 0, 'SELECT 1', 'SELECT `中止：目标字典项存在重复，请先人工去重`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 菜单 ID 占位阻断
SET @m_taken := (SELECT COUNT(*) FROM api_rbac_menu
                 WHERE ID IN (1024, 1025, 1026, 1142, 1143, 1144)
                   AND NOT (MENU_API_PERMS IN ('device:alarm:handle','device:workorder:handle','device:batch:execute')
                            OR MENU_PATH IN ('alarm','workorder','batch')));
SET @sql := IF(@m_taken = 0, 'SELECT 1', 'SELECT `中止：菜单 ID 1024-1026/1142-1144 已被其它功能占用`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 变更区 ============

-- ---------- 1. ws_alarm ----------
SET @sql := IF(@done_alarm = 1, 'SELECT 1',
  'ALTER TABLE `ws_alarm`
     ADD COLUMN `ACTIVE_DEDUPE_KEY` varchar(100) NULL COMMENT ''活动告警幂等键：AlarmDedupeKey 单一出处生成（类型:设备[:来源]）。活动期间非空且全库唯一，忽略/自动恢复时原子清空'' AFTER `RECOVER_TIME`,
     ADD COLUMN `HANDLE_BY` bigint NULL COMMENT ''处置人（api_employee.ID；忽略/转工单时回填）'' AFTER `ACTIVE_DEDUPE_KEY`,
     ADD COLUMN `HANDLE_TIME` varchar(14) NULL COMMENT ''处置时间'' AFTER `HANDLE_BY`,
     ADD UNIQUE INDEX `uk_alarm_active_dedupe` (`ACTIVE_DEDUPE_KEY`)');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 存量活动告警（1待处理/2已转工单）回填活动键，口径与 AlarmDedupeKey 一致：类型:设备[:来源]。
-- 终态告警（3已忽略/4自动恢复）保持 NULL——历史可重复留存正是可空唯一键的设计意图。
UPDATE ws_alarm
SET ACTIVE_DEDUPE_KEY = CONCAT(ALARM_TYPE, ':', DEVICE_ID,
                               IF(SOURCE_REF IS NULL OR SOURCE_REF = '', '', CONCAT(':', SOURCE_REF)))
WHERE ALARM_STATUS IN (1, 2) AND DATA_STATUS = 0 AND ACTIVE_DEDUPE_KEY IS NULL;

-- ---------- 2. ws_work_order ----------
SET @done_wo := (SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'ws_work_order'
                   AND column_name = 'WORK_TYPE');
SET @sql := IF(@done_wo = 1, 'SELECT 1',
  'ALTER TABLE `ws_work_order`
     ADD COLUMN `WORK_TYPE` tinyint NOT NULL DEFAULT 1 COMMENT ''工单类型(1365)：1维修 2配件 3巡检'' AFTER `ORDER_NO`,
     ADD COLUMN `APPLICANT_USER_ID` bigint NULL COMMENT ''申报人（ws_user.ID）；机主申报时回填，身份只取 KH_USER 会话'' AFTER `ALARM_ID`,
     ADD COLUMN `REQUEST_ID` varchar(64) NULL COMMENT ''机主申报幂等键；重复提交返回原工单'' AFTER `APPLICANT_USER_ID`,
     ADD COLUMN `RESULT_PHOTOS` text NULL COMMENT ''处理证据媒体键JSON数组（受控 mediaKey；员工身份登记）'' AFTER `FINISH_RESULT`,
     ADD COLUMN `REVIEW_BY` bigint NULL COMMENT ''复核人（api_employee.ID）'' AFTER `RESULT_PHOTOS`,
     ADD COLUMN `REVIEW_TIME` varchar(14) NULL COMMENT ''复核时间（通过或退回都回填）'' AFTER `REVIEW_BY`,
     ADD COLUMN `REVIEW_REMARK` varchar(500) NULL COMMENT ''复核意见(max500)；复核退回时必填'' AFTER `REVIEW_TIME`,
     ADD COLUMN `REJECT_REASON` varchar(500) NULL COMMENT ''驳回原因(max500)；待确认→已驳回时必填'' AFTER `REVIEW_REMARK`,
     ADD COLUMN `CLOSE_TIME` varchar(14) NULL COMMENT ''关闭时间（复核通过后回填）'' AFTER `REJECT_REASON`,
     ADD COLUMN `VERSION` int NOT NULL DEFAULT 1 COMMENT ''乐观锁版本；全部状态迁移经 VERSION+前态 CAS'' AFTER `CLOSE_TIME`,
     DROP INDEX `idx_wo_no`,
     ADD UNIQUE INDEX `uk_wo_no` (`ORDER_NO`),
     ADD UNIQUE INDEX `uk_wo_alarm` (`ALARM_ID`),
     ADD UNIQUE INDEX `uk_wo_request` (`REQUEST_ID`),
     ADD INDEX `idx_wo_applicant` (`APPLICANT_USER_ID`)');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 状态映射从大值往小值，防二次搬运；只在首次执行（@done_wo=0 时列刚建出）做。
-- 幂等保护：重复执行时 @done_wo=1，映射被跳过——否则新口径的 3处理中 会被再搬成 4待复核。
SET @sql := IF(@done_wo = 1, 'SELECT 1',
               'UPDATE ws_work_order SET ORDER_STATUS = 5 WHERE ORDER_STATUS = 4');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@done_wo = 1, 'SELECT 1',
               'UPDATE ws_work_order SET ORDER_STATUS = 4, FINISH_TIME = IFNULL(FINISH_TIME, UPDATE_TIME) WHERE ORDER_STATUS = 3');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@done_wo = 1, 'SELECT 1',
               'UPDATE ws_work_order SET ORDER_STATUS = 3 WHERE ORDER_STATUS = 2');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@done_wo = 1, 'SELECT 1',
               'UPDATE ws_work_order SET ORDER_STATUS = 2 WHERE ORDER_STATUS = 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 3. ws_command ----------
SET @done_cmd := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE() AND table_name = 'ws_command'
                    AND column_name = 'BATCH_ID');
SET @sql := IF(@done_cmd = 1, 'SELECT 1',
  'ALTER TABLE `ws_command`
     ADD COLUMN `BATCH_ID` bigint NULL COMMENT ''批量任务ID(ws_command_batch.ID)；单发指令为NULL'' AFTER `RETRY_COUNT`,
     ADD UNIQUE INDEX `uk_cmd_batch_device` (`BATCH_ID`, `DEVICE_ID`)');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 4. ws_command_batch ----------
CREATE TABLE IF NOT EXISTS `ws_command_batch` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID（api_employee.ID，发起批量的运营）',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `BATCH_NO`         varchar(32)  NOT NULL COMMENT '批次号(max32)，系统生成，三端贯穿',
  `SCOPE_TYPE`       tinyint      NOT NULL COMMENT '范围类型(1366)：1指定设备 2指定水站 3全部设备',
  `SCOPE_SNAPSHOT`   text         NOT NULL COMMENT '目标快照JSON：{deviceIds:[...],stationId?}——confirm 时后端解析结果冻结',
  `CMD_TYPE`         tinyint      NOT NULL COMMENT '指令类型(1320)：批量只允许 3查询 4锁机 5解锁 6参数同步 7重启 8价格同步',
  `CMD_PAYLOAD`      text         COMMENT '参数本体JSON（参数同步/价格同步携带）',
  `PARAM_DIGEST`     char(64)     NOT NULL COMMENT '命令类型+参数摘要 sha256：ticket 绑定与 confirm 一致性校验依据',
  `TOTAL_COUNT`      int          NOT NULL COMMENT '子指令总数（=目标设备数）',
  `SUCCESS_COUNT`    int          NOT NULL DEFAULT 0 COMMENT '成功数',
  `FAIL_COUNT`       int          NOT NULL DEFAULT 0 COMMENT '失败数（含设备拒绝/执行失败）',
  `TIMEOUT_COUNT`    int          NOT NULL DEFAULT 0 COMMENT '超时数',
  `BATCH_STATUS`     tinyint      NOT NULL COMMENT '聚合状态(1367)：1处理中 2全部成功 3部分成功 4全部失败',
  `FINISH_TIME`      varchar(14)  COMMENT '聚合终态时间',
  `VERSION`          int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本；聚合数量与状态经 CAS 回写',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_cbatch_no` (`BATCH_NO`),
  INDEX `idx_cbatch_status` (`BATCH_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='批量指令任务表（E2E-05）';

-- ---------- 5. ws_device SIM ----------
SET @done_dev := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE() AND table_name = 'ws_device'
                    AND column_name = 'SIM_STATUS');
SET @sql := IF(@done_dev = 1, 'SELECT 1',
  'ALTER TABLE `ws_device`
     ADD COLUMN `SIM_STATUS` tinyint NULL COMMENT ''SIM状态(1368)：1正常 2未激活 3欠费 4停用；档案维护或模拟器口径'' AFTER `SIM_CARRIER`,
     ADD COLUMN `SIM_EXPIRE_TIME` varchar(14) NULL COMMENT ''SIM到期时间；到期临近由告警引擎产SIM异常告警'' AFTER `SIM_STATUS`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 状态报文乱序保护必须独立于 SIM 字段判断，保证已经执行过早期版本的环境重放迁移时也能补齐。
SET @done_dev_status_time := (SELECT COUNT(*) FROM information_schema.columns
                              WHERE table_schema = DATABASE() AND table_name = 'ws_device'
                                AND column_name = 'LAST_STATUS_DEVICE_TIME');
SET @sql := IF(@done_dev_status_time = 1, 'SELECT 1',
  'ALTER TABLE `ws_device`
     ADD COLUMN `LAST_STATUS_DEVICE_TIME` varchar(14) NULL COMMENT ''最近一次已应用状态报文的设备时间；乱序补传不得覆盖当前状态'' AFTER `LAST_FAULT_CODE`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 6. ws_delivery_media ----------
SET @done_media := (SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_media'
                      AND column_name = 'OWNER_PORTAL');
SET @sql := IF(@done_media = 1, 'SELECT 1',
  'ALTER TABLE `ws_delivery_media`
     ADD COLUMN `OWNER_PORTAL` tinyint NOT NULL DEFAULT 2 COMMENT ''登记人门户(1364)：2用户 1管理端（工单处理证据）。员工ID与用户ID数值可能相同，缺本列则跨身份空间撞媒体键'' AFTER `OWNER_USER_ID`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 7. 字典（NOT EXISTS 幂等；api_dict_data 无唯一键，IGNORE 无键可撞） ----------
-- 7.1 工单状态 1362：数据重写为六状态。先按值改写既有行（保留 ID），再补缺失值。
UPDATE api_dict_data SET DICT_LABEL = '待确认' WHERE DICT_TYPE = '1362' AND DICT_VALUE = 1 AND DICT_LABEL <> '待确认';
UPDATE api_dict_data SET DICT_LABEL = '待分配' WHERE DICT_TYPE = '1362' AND DICT_VALUE = 2 AND DICT_LABEL <> '待分配';
UPDATE api_dict_data SET DICT_LABEL = '处理中' WHERE DICT_TYPE = '1362' AND DICT_VALUE = 3 AND DICT_LABEL <> '处理中';
UPDATE api_dict_data SET DICT_LABEL = '待复核' WHERE DICT_TYPE = '1362' AND DICT_VALUE = 4 AND DICT_LABEL <> '待复核';
INSERT INTO api_dict_data(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, '1362', 5, 5, '已关闭'
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = '1362' AND d.DICT_VALUE = 5);
INSERT INTO api_dict_data(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, '1362', 6, 6, '已驳回'
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = '1362' AND d.DICT_VALUE = 6);
UPDATE api_dict_type SET DICT_REMARK = '运维工单状态机（六状态）' WHERE DICT_TYPE = '1362';

-- 7.2 新字典 1365/1366/1367/1368
INSERT INTO api_dict_type(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (
  SELECT '工单类型' AS DICT_NAME, '1365' AS DICT_TYPE, '运维工单类型' AS DICT_REMARK
  UNION ALL SELECT '批量指令范围', '1366', '批量设备控制的目标范围类型'
  UNION ALL SELECT '批量指令聚合状态', '1367', '批量任务的聚合执行状态'
  UNION ALL SELECT 'SIM状态', '1368', '设备SIM卡运维状态（档案维护/模拟器口径，真实运营商查询未接入）'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO api_dict_data(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
  SELECT '1365' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '维修' AS DICT_LABEL
  UNION ALL SELECT '1365', 2, 2, '配件'
  UNION ALL SELECT '1365', 3, 3, '巡检'
  UNION ALL SELECT '1366', 1, 1, '指定设备'
  UNION ALL SELECT '1366', 2, 2, '指定水站'
  UNION ALL SELECT '1366', 3, 3, '全部设备'
  UNION ALL SELECT '1367', 1, 1, '处理中'
  UNION ALL SELECT '1367', 2, 2, '全部成功'
  UNION ALL SELECT '1367', 3, 3, '部分成功'
  UNION ALL SELECT '1367', 4, 4, '全部失败'
  UNION ALL SELECT '1368', 1, 1, '正常'
  UNION ALL SELECT '1368', 2, 2, '未激活'
  UNION ALL SELECT '1368', 3, 3, '欠费'
  UNION ALL SELECT '1368', 4, 4, '停用'
) s
WHERE NOT EXISTS (
  SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE
);

-- 7.3 指令类型 1320 补 8价格同步；事件类型 1363 补 10告警状态变化
INSERT INTO api_dict_data(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, '1320', 8, 8, '价格同步'
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = '1320' AND d.DICT_VALUE = 8);
INSERT INTO api_dict_data(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, '1363', 10, 10, '告警状态变化'
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = '1363' AND d.DICT_VALUE = 10);

-- ---------- 8. 菜单与权限（列清单与 03-demo-baseline 逐列一致；插入+复活+绑定） ----------
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,
 `MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,
 `DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`) VALUES
(1024,'告警中心',2,NULL,1001,6,'alarm','/device/alarm',1,NULL,NULL,1,1,0,1,'20260730120000',1,'20260730120000'),
(1025,'运维工单',2,NULL,1001,5,'workorder','/device/workorder',1,NULL,NULL,1,1,0,1,'20260730120000',1,'20260730120000'),
(1026,'批量控制',2,NULL,1001,0,'batch','/device/batch',1,NULL,NULL,1,1,0,1,'20260730120000',1,'20260730120000'),
(1142,'告警处置',3,NULL,1024,1,NULL,NULL,1,'device:alarm:handle','device:alarm:handle',2,1,0,1,'20260730120000',1,'20260730120000'),
(1143,'工单处理',3,NULL,1025,1,NULL,NULL,1,'device:workorder:handle','device:workorder:handle',2,1,0,1,'20260730120000',1,'20260730120000'),
(1144,'批量指令执行',3,NULL,1026,1,NULL,NULL,1,'device:batch:execute','device:batch:execute',2,1,0,1,'20260730120000',1,'20260730120000');

-- 曾被逻辑删除的绑定先复活（NOT EXISTS 只看有没有行，留 DATA_STATUS=1 的旧绑定会让插入被跳过）
UPDATE `api_rbac_role_menu`
SET `DATA_STATUS` = 0, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260730120000'
WHERE `ROLE_ID` = 1 AND `MENU_ID` IN (1024, 1025, 1026, 1142, 1143, 1144) AND `DATA_STATUS` <> 0;

INSERT INTO `api_rbac_role_menu`
(`DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `ROLE_ID`, `MENU_ID`)
SELECT 0, 1, '20260730120000', 1, '20260730120000', 1, m.ID
FROM api_rbac_menu m
WHERE m.ID IN (1024, 1025, 1026, 1142, 1143, 1144)
  AND NOT EXISTS (SELECT 1 FROM api_rbac_role_menu rm WHERE rm.ROLE_ID = 1 AND rm.MENU_ID = m.ID);

-- ============ 后置不变式 ============

SET @ok_dedupe := (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'ws_alarm'
                     AND INDEX_NAME = 'uk_alarm_active_dedupe' AND NON_UNIQUE = 0);
SET @sql := IF(@ok_dedupe = 1, 'SELECT 1', 'SELECT `中止：uk_alarm_active_dedupe 未建立，并发告警仍可重复`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 活动告警必须都有活动键（键法回填成功的证据）
SET @orphan_active := (SELECT COUNT(*) FROM ws_alarm
                       WHERE ALARM_STATUS IN (1, 2) AND DATA_STATUS = 0 AND ACTIVE_DEDUPE_KEY IS NULL);
SET @sql := IF(@orphan_active = 0, 'SELECT 1', 'SELECT `中止：存在无活动键的活动告警，回填不完整`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_wo_uk := (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.statistics
                  WHERE table_schema = DATABASE() AND table_name = 'ws_work_order'
                    AND INDEX_NAME IN ('uk_wo_no', 'uk_wo_alarm', 'uk_wo_request') AND NON_UNIQUE = 0);
SET @sql := IF(@ok_wo_uk = 3, 'SELECT 1', 'SELECT `中止：工单三把唯一键不齐`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 工单状态值必须全部落在六状态域内（映射完整性的证据）
SET @wo_bad_status := (SELECT COUNT(*) FROM ws_work_order WHERE ORDER_STATUS NOT IN (1,2,3,4,5,6));
SET @sql := IF(@wo_bad_status = 0, 'SELECT 1', 'SELECT `中止：存在六状态域外的工单状态值，映射不完整`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_batch := (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'ws_command_batch');
SET @sql := IF(@ok_batch = 1, 'SELECT 1', 'SELECT `中止：ws_command_batch 建表未生效`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_cmd_uk := (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.statistics
                   WHERE table_schema = DATABASE() AND table_name = 'ws_command'
                     AND INDEX_NAME = 'uk_cmd_batch_device' AND NON_UNIQUE = 0);
SET @sql := IF(@ok_cmd_uk = 1, 'SELECT 1', 'SELECT `中止：uk_cmd_batch_device 未建立，批量重放会重复下发`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_status_time := (SELECT COUNT(*) FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 'ws_device'
                          AND column_name = 'LAST_STATUS_DEVICE_TIME');
SET @sql := IF(@ok_status_time = 1, 'SELECT 1',
               'SELECT `中止：ws_device.LAST_STATUS_DEVICE_TIME 未建立，状态补传仍可回滚当前投影`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_1362 := (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE = '1362');
SET @sql := IF(@ok_1362 = 6, 'SELECT 1', 'SELECT `中止：工单状态字典不为6项`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_newdict := (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE IN ('1365','1366','1367','1368'));
SET @sql := IF(@ok_newdict = 14, 'SELECT 1', 'SELECT `中止：新字典项数不为14`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_perm := (SELECT COUNT(*) FROM api_rbac_menu
                 WHERE MENU_TYPE = 3 AND DATA_STATUS = 0
                   AND MENU_API_PERMS IN ('device:alarm:handle','device:workorder:handle','device:batch:execute'));
SET @sql := IF(@ok_perm = 3, 'SELECT 1', 'SELECT `中止：三个设备运维权限码不齐`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_bind := (SELECT COUNT(*) FROM api_rbac_role_menu
                 WHERE ROLE_ID = 1 AND MENU_ID IN (1024,1025,1026,1142,1143,1144) AND DATA_STATUS = 0);
SET @sql := IF(@ok_bind = 6, 'SELECT 1', 'SELECT `中止：新菜单未全部绑定超管角色`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT 'E2E-05 包A 迁移完成：告警活动键 + 工单六状态 + 批量指令 + SIM运维 + 媒体门户 + 字典/菜单' AS RESULT;

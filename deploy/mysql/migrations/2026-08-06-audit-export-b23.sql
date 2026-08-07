-- ============================================================
-- 审计导出任务落库（B23 / REQ-024、REQ-066，2026-08-06）
--
-- 背景：合规页的导出申请此前只存在于前端内存数组，刷新即失——"谁在什么时候申请导出了什么"
-- 这项最有合规价值的事实完全没有留痕，且与操作日志、领域事件构成第二个互不相通的审计源。
-- 本迁移把申请事实落库，使三者同源可查（R-204）。
--
-- 【能力边界】文件生成依赖对象存储，尚未接入。任务只能停在 1待生成；
-- FILE_DIGEST/EXPIRE_TIME 只有真实文件产出后才允许写入。平台不得自行推进到 4已生成。
--
-- 与 init/02-ws-business.sql 的表结构与字典逐字一致（双轨，SchemaParityTest 看守）。
-- 幂等：重复执行结果相同（建表 IF NOT EXISTS；字典 NOT EXISTS——**不是** INSERT IGNORE，
-- 原因见下方字典段注释；本迁移不带种子数据）。
--
-- 【执行方式】mysql -h127.0.0.1 -P3308 -uroot -p"$DAKANG_DB_PASSWORD" dakang < 本文件
-- 本文件不含 `$` 字面量，但沿用同一约定以免形成例外。
--
-- 【顺序：先库后包】build-all.sh 全程没有迁移步骤，只 copy jar 并重启容器。
-- 含 B23 的新 JAR 一旦先上主环境而本迁移未执行，/api/auditExport/pageData 会直接
-- 1146 Table 'dakang.ws_audit_export_task' doesn't exist——REQ-213 刚因同一形状踩过一次
-- （见 SchemaParityTest 类注释）。故必须先执行本迁移，再 build-all.sh 部署。
-- 反向顺序（先跑迁移、后换包）是安全的：旧 JAR 不认识这张表，多一张空表无副作用。
-- ============================================================

SET NAMES utf8mb4;

-- 前置一：字典编号 1382 不得已被他人占用（撞号会让两套枚举共用一个字典）
SET @conflict := (SELECT COUNT(*) FROM `api_dict_type`
  WHERE `DICT_TYPE` = '1382' AND `DICT_NAME` <> '审计导出任务状态');
SET @sql := IF(@conflict = 0, 'SELECT 1',
  'SELECT `中止：字典编号1382已被其他业务占用，请改用空号并同步 ApiEnum.DictType` AS abort_reason');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 前置二：1382 字典若已存在重复行，先人工去重再跑——本迁移的 NOT EXISTS 只保证"不再新增"，
-- 不会替既有污染收尾，而重复行会让 ApiDictTypeServiceImpl 的 selectJoinOne 直接 TooManyResults(500)
SET @dup_type := (SELECT COUNT(*) FROM `api_dict_type` WHERE `DICT_TYPE` = '1382');
SET @dup_data := (SELECT COUNT(*) FROM `api_dict_data` WHERE `DICT_TYPE` = '1382');
SET @sql := IF(@dup_type <= 1 AND @dup_data <= 5, 'SELECT 1',
  'SELECT `中止：字典1382已存在重复行（历史 INSERT IGNORE 污染），请先去重至 type=1/data=5 再执行` AS abort_reason');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 建表 ----------
CREATE TABLE IF NOT EXISTS `ws_audit_export_task` (
  `ID`             bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`    tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`      bigint       NOT NULL COMMENT '创建人ID（申请员工）',
  `CREATE_TIME`    varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`      bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`    varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `TASK_NO`        varchar(40)  NOT NULL COMMENT '任务号，格式 AUD-EXP-yyyyMMdd-NNN，业务唯一',
  `EXPORT_SCOPE`   varchar(200) NOT NULL COMMENT '导出范围，多项以、分隔(max200)',
  `FILTER_SUMMARY` varchar(500) NOT NULL COMMENT '筛选条件快照：时间区间与关键字，申请时冻结(max500)',
  `APPLY_REASON`   varchar(500) NOT NULL COMMENT '申请原因，合规留痕必填(max500)',
  `MASKING_RULE`   varchar(200) NOT NULL COMMENT '脱敏规则快照，随申请时点固化(max200)',
  `TASK_STATUS`    tinyint      NOT NULL COMMENT '任务状态(1382)：1待生成 2生成中 3失败 4已生成 5已过期',
  `APPLY_BY_NAME`  varchar(30)  NOT NULL COMMENT '申请人姓名快照，避免员工改名后追溯串味(max30)',
  `FAILURE_REASON` varchar(500) DEFAULT NULL COMMENT '失败原因；仅 3失败 时非空(max500)',
  `FILE_DIGEST`    varchar(128) DEFAULT NULL COMMENT '导出文件摘要；只有真实文件产出后才写入，对象存储未接入时恒为NULL',
  `EXPIRE_TIME`    varchar(14)  DEFAULT NULL COMMENT '下载过期时间；同 FILE_DIGEST，无真实文件即为NULL',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_audit_export_task_no` (`TASK_NO`),
  -- 合规页默认列表：无状态过滤、按 CREATE_TIME DESC + ID DESC 排序。
  -- 二级索引隐含主键，故该索引可反向扫描直接满足排序，避免全表扫 + filesort
  INDEX `idx_audit_export_create_time` (`CREATE_TIME`),
  -- 按状态筛选时走这条（等值 + 反向扫描满足时间倒序）
  INDEX `idx_audit_export_status_time` (`TASK_STATUS`, `CREATE_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='审计导出任务：申请留痕与状态追踪；文件生成待对象存储接入';

-- ---------- 字典 ----------
-- 【必须 NOT EXISTS，不能 INSERT IGNORE】api_dict_type / api_dict_data 除主键外没有任何唯一键
-- （自增 ID 每次都不同），IGNORE 无键可撞 → 重复执行静默插入整套重复项，而
-- ApiDictTypeServiceImpl 的 selectJoinOne 遇重复行直接 TooManyResults(500)，整个字典接口挂掉。
-- 首版本文件正是用了 IGNORE，实测在验收环境（init 灌一套 + 本迁移再灌一套）当场翻倍成 2/10 行。
-- 同一教训已记录在 2026-07-29-aftersale-e2e04-a.sql:167-170，此处沿用其 NOT EXISTS 范式。
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (
  SELECT '审计导出任务状态' AS DICT_NAME, '1382' AS DICT_TYPE,
         'ws_audit_export_task.TASK_STATUS；4已生成仅在真实文件产出后允许出现' AS DICT_REMARK
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1382' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '待生成' AS DICT_LABEL
  UNION ALL SELECT '1382', 2, 2, '生成中'
  UNION ALL SELECT '1382', 3, 3, '失败'
  UNION ALL SELECT '1382', 4, 4, '已生成'
  UNION ALL SELECT '1382', 5, 5, '已过期'
) s
WHERE NOT EXISTS (
  SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

-- ---------- 后置不变式（中止式，不是只打印）----------
-- 裸 SELECT 只会把数字打在屏幕上，mysql 照样零退出、acc-env.sh 照样报"重建完成"——
-- 首版正是如此，字典翻倍跑了整轮都没红灯。故改为 PREPARE 中止范式。
SET @ok_table := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
  AND table_name = 'ws_audit_export_task');
SET @ok_dict_type := (SELECT COUNT(*) FROM `api_dict_type` WHERE `DICT_TYPE` = '1382');
SET @ok_dict_data := (SELECT COUNT(*) FROM `api_dict_data` WHERE `DICT_TYPE` = '1382');
-- 伪造终态判据覆盖两种形态：DONE(4)/EXPIRED(5) 均不可达，且无文件时两个文件字段必须为空
SET @forged := (SELECT COUNT(*) FROM `ws_audit_export_task`
  WHERE `TASK_STATUS` IN (4, 5) OR `FILE_DIGEST` IS NOT NULL OR `EXPIRE_TIME` IS NOT NULL);
SET @sql := IF(@ok_table = 1 AND @ok_dict_type = 1 AND @ok_dict_data = 5 AND @forged = 0, 'SELECT 1',
  'SELECT `中止：后置不变式未满足（表缺失/字典行数非1与5/存在伪造的已生成终态或文件摘要）` AS abort_reason');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT @ok_table AS table_present, @ok_dict_type AS dict_type_rows,
       @ok_dict_data AS dict_data_rows, @forged AS forged_done;

SELECT '2026-08-06 迁移完成：审计导出任务表与字典就绪（文件生成待对象存储）' AS RESULT;

-- ============================================================
-- 【仅限空库】本文件以 DROP TABLE IF EXISTS 开头，只能用于全新空库。
-- 对既有库（主库 3308／验收库 3309／任何已部署环境）执行会静默删光本域全部数据，
-- 无任何提示、不可恢复。既有库一律改走 deploy/mysql/migrations/ 下的非破坏迁移。
-- 详见 AGENTS.md「第 1 步：数据库设计」第 5 条。
-- 本域既有库迁移：deploy/mysql/migrations/2026-08-06-audit-export-b23.sql
-- ============================================================
-- 审计导出任务（B23 / REQ-024、REQ-066）
--
-- 背景：合规页的导出申请此前只存在于前端内存数组，刷新即失，等于"申请了什么、谁申请的"
-- 完全没有留痕——而这恰恰是审计导出最有合规价值的部分。本表把申请事实落库，
-- 使之与操作日志（api_log_operation）、领域事件（ws_domain_event）同源可查。
--
-- 【能力边界】文件生成依赖对象存储，尚未接入。因此：
--   - 任务创建后只能停在 1待生成，平台不得自行推进到 4已生成；
--   - FILE_DIGEST / EXPIRE_TIME 只有在真实文件产出后才允许写入；
--   - 页面不得提供下载入口，也不得展示伪造的"导出成功"。
-- 这与退款链 Refund-Sim/微信适配器的处置一致：能力未接入就 fail-closed，不伪造终态。
-- ============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS `ws_audit_export_task`;
CREATE TABLE `ws_audit_export_task` (
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

-- ---------- 字典（1382，空号；1376~1381 为 E2E-08 分账域已占用）----------
-- 必须 NOT EXISTS 而非 INSERT IGNORE：api_dict_type/api_dict_data 除主键外无任何唯一键，
-- IGNORE 无键可撞，重复执行会静默翻倍，而字典查询 selectJoinOne 遇重复行即 500。
-- 教训出处：2026-07-29-aftersale-e2e04-a.sql:167-170（本模块首版曾再犯一次）。
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

-- ---------- 测试数据 ----------
-- 6 条，全部落在**当前可达**的两个状态（1待生成 / 3失败）。
--
-- 为什么不是"覆盖 5 个枚举值"：mysql8 skill 要求 5~10 条覆盖各枚举值与边界，但
-- 2生成中/4已生成/5已过期 在对象存储接入前服务端没有任何路径可达（见 ComplianceEnum 注释），
-- 造出来就是页面上一条永远点不动、也没有文件的"已生成"——正是 B23 要消灭的伪审计源，
-- 也会撞上迁移的 forged_done 后置不变式。故条数补在**边界**而非枚举值上：
--   #1 失败态（retry 动线的样本）        #2 单范围 + 无关键字
--   #3 六范围全选（EXPORT_SCOPE 满值）   #4 双关键字（FILTER_SUMMARY 较长）
--   #5 第二条失败态（#1 被 retry 消耗后仍有失败样本可看）
--   #6 跨天任务号（验证 -001 序号按天重新起算）
-- 关键字一律已按 MASKING_RULE 脱敏，与服务端 buildFilterSummary 的落库形态一致。
INSERT IGNORE INTO `ws_audit_export_task`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`TASK_NO`,`EXPORT_SCOPE`,`FILTER_SUMMARY`,`APPLY_REASON`,`MASKING_RULE`,`TASK_STATUS`,`APPLY_BY_NAME`,`FAILURE_REASON`,`FILE_DIGEST`,`EXPIRE_TIME`) VALUES
(1,0,1,'20260714121000',1,'20260714121000','AUD-EXP-20260714-001','操作日志、订单追溯','20260710000000 至 20260714120000；业务对象 WO20260712','核对异常取水订单的操作与状态变化记录','手机号中间四位脱敏；身份信息不导出',3,'超级管理员','文件生成器与对象存储尚未接入',NULL,NULL),
(2,0,1,'20260714140500',1,'20260714140500','AUD-EXP-20260714-002','设备事件','20260714000000 至 20260714140000','复核离线设备的指令超时与故障告警证据','手机号中间四位脱敏；身份信息不导出',1,'超级管理员',NULL,NULL,NULL),
(3,0,1,'20260714150000',1,'20260714150000','AUD-EXP-20260714-003','操作日志、登录日志、订单追溯、设备事件、指令回执、领域事件','20260701000000 至 20260714150000','季度合规抽查：全量范围留痕核对','手机号中间四位脱敏；身份信息不导出',1,'超级管理员',NULL,NULL,NULL),
(4,0,1,'20260714160000',1,'20260714160000','AUD-EXP-20260714-004','登录日志','20260713000000 至 20260714160000；操作人 139****1111；业务对象 DK-DEV-0002','排查异常登录与设备操作的关联','手机号中间四位脱敏；身份信息不导出',1,'超级管理员',NULL,NULL,NULL),
(5,0,1,'20260714170000',1,'20260714170000','AUD-EXP-20260714-005','领域事件','20260714000000 至 20260714170000','复核领域事件白名单导出口径','手机号中间四位脱敏；身份信息不导出',3,'超级管理员','文件生成器与对象存储尚未接入',NULL,NULL),
(6,0,1,'20260715090000',1,'20260715090000','AUD-EXP-20260715-001','指令回执','20260715000000 至 20260715090000','跨天序号验证：任务号按天重新起算','手机号中间四位脱敏；身份信息不导出',1,'超级管理员',NULL,NULL,NULL);

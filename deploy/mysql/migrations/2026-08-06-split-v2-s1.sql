-- ============================================================
-- 分润 V2 S1：完整计划模型与组件证据表（E2E-08，2026-08-06 甲方会议口径）
--
-- 新增三张纯增量表，不触碰任何既有表：
--   ws_split_plan       完整计划头（整版发布，纠 V1 各收款方独立选版本之偏）
--   ws_split_plan_item  计划项（线×角色×层级×万分比×模式）
--   ws_split_component  组件证据（谁以什么角色从哪条基数拿多少钱）
--
-- 【不插任何计划种子】正式比例待甲方书面确认（任务书 5.1）；会议中的
-- 50%/5%/10-8-5 全部是讨论示例。本迁移只建结构。
--
-- 【与既有分账的关系】ws_split_record 状态机不动；组件是计算证据，
-- S2 才按收益人聚合为待入账行。V2 开关（settlement.split-v2.enabled）
-- 两环境默认 false，本迁移执行与否都不改变现有分账行为。
--
-- 幂等：CREATE TABLE IF NOT EXISTS，重复执行零变更。
-- 前置守卫：若存量已有同名表但缺关键唯一键（说明是手工建的旧结构），中止而非带病继续。
-- ============================================================

SET NAMES utf8mb4;

-- 前置：存量 ws_split_component 若已存在，必须带 COMPONENT_KEY 唯一键，
-- 否则并发防重复的地基就是空的——中止，人工核对后再迁
SET @tbl := (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'ws_split_component');
SET @uk := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_split_component'
    AND index_name = 'uk_split_component_key' AND non_unique = 0);
SET @sql := IF(@tbl = 0 OR @uk > 0, 'SELECT 1',
  'SELECT `中止：存量 ws_split_component 缺 COMPONENT_KEY 唯一键，结构不符预期，请人工核对后再迁移` AS abort_reason');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 计划头 ----------
CREATE TABLE IF NOT EXISTS `ws_split_plan` (
  `ID`           bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`  tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`    bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`  varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`    bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`  varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `PLAN_VERSION` varchar(20)  NOT NULL COMMENT '计划版本号，业务唯一',
  `EFFECT_TIME`  varchar(14)  NOT NULL COMMENT '生效时间yyyyMMddHHmmss',
  `PLAN_STATUS`  tinyint      NOT NULL COMMENT '计划状态：1草稿 2生效 3停用；整版发布，禁止半套生效',
  `PLAN_REMARK`  varchar(200) DEFAULT NULL COMMENT '备注(max200)',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_split_plan_version` (`PLAN_VERSION`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='分润V2完整计划头：整版发布整版生效，正式比例未确认前不得有生效行';

-- ---------- 计划项 ----------
CREATE TABLE IF NOT EXISTS `ws_split_plan_item` (
  `ID`           bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`  tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`    bigint      NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`  varchar(14) NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`    bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`  varchar(14) NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `PLAN_ID`      bigint      NOT NULL COMMENT '所属计划ID(ws_split_plan.ID)',
  `PRODUCT_LINE` varchar(16) NOT NULL COMMENT '基数线：WATER_SALE售水/DELIVERY_FEE配送费，两线独立绝不合并',
  `ROLE_CODE`    varchar(32) NOT NULL COMMENT '角色：WATER_OWNER/WATER_DIRECT_REFERRER/REGION_PROVINCE/REGION_CITY/REGION_COUNTY/DELIVERY_COURIER',
  `REGION_LEVEL` varchar(16) NOT NULL DEFAULT 'NONE' COMMENT '区域层级：NONE/PROVINCE/CITY/COUNTY，非区域角色恒NONE',
  `RATE_BP`      int         NOT NULL COMMENT '万分比0..10000；级差模式下为该层累计上限，实得由计算器求级差',
  `RATE_MODE`    varchar(24) NOT NULL COMMENT '比例模式：FIXED固定/REGIONAL_CUMULATIVE区域级差累计',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_split_plan_item` (`PLAN_ID`, `PRODUCT_LINE`, `ROLE_CODE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='分润V2计划项：整版校验后发布，缺项即整版拒绝，绝不静默按0';

-- ---------- 组件证据 ----------
CREATE TABLE IF NOT EXISTS `ws_split_component` (
  `ID`                 bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`        tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`          bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`        varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`          bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`        varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `ORDER_ID`           bigint       NOT NULL COMMENT '订单ID',
  `ORDER_NO`           varchar(40)  NOT NULL COMMENT '订单号',
  `PRODUCT_LINE`       varchar(16)  NOT NULL COMMENT '基数线：WATER_SALE/DELIVERY_FEE',
  `BASIS_AMOUNT`       bigint       NOT NULL COMMENT '该线权威基数金额（整数分，实收口径）',
  `ROLE_CODE`          varchar(32)  NOT NULL COMMENT '角色编码；PLATFORM_REMAINDER为平台余数行',
  `RECEIVER_USER_ID`   bigint       NOT NULL COMMENT '收益人用户ID；平台余数行=0哨兵（NULL不参与唯一约束，沿用V1教训）',
  `EFFECTIVE_RATE`     int          NOT NULL COMMENT '实际生效万分比；级差角色为级差后实得；平台余数行=-1',
  `SPLIT_AMOUNT`       bigint       NOT NULL COMMENT '分得金额（整数分，非平台向下取整，余数归平台）',
  `PLAN_VERSION`       varchar(20)  NOT NULL COMMENT '计划版本号（证据锚点）',
  `ATTRIBUTION_SOURCE` varchar(20)  NOT NULL COMMENT '归属来源：PRIVATE_REFERRAL/PUBLIC_UNASSIGNED/PUBLIC_MANUAL',
  `COMPONENT_KEY`      varchar(120) NOT NULL COMMENT '幂等键 SPLITV2:订单号:线:角色',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_split_component_key` (`COMPONENT_KEY`),
  INDEX `idx_split_component_order` (`ORDER_ID`),
  INDEX `idx_split_component_receiver` (`RECEIVER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='分润V2组件证据：计算明细留痕，不代替 ws_split_record 付款状态机';

-- ---------- 后置不变式 ----------
-- tables=3；component_uk=1；active_plans 必须 0（正式比例未确认，不得有生效计划）
SELECT
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
    AND table_name IN ('ws_split_plan','ws_split_plan_item','ws_split_component')) AS v2_tables,
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'ws_split_component' AND index_name = 'uk_split_component_key'
    AND non_unique = 0) AS component_uk,
  (SELECT COUNT(*) FROM `ws_split_plan` WHERE `PLAN_STATUS` = 2) AS active_plans;

SELECT '2026-08-06 迁移完成：分润V2 S1 三表就绪（无种子计划，开关默认关闭）' AS RESULT;

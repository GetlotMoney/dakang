-- ============================================================
-- E2E-09 商城 S3：前置仓履约与配送签收（既有库增量迁移）
--
-- 依赖：本库已执行 2026-08-08-mall-s1.sql 与 2026-08-08-mall-s2.sql。
-- 与 server/sql/ws_mall.sql（领域源，仅限空库）和 deploy/mysql/init/02-ws-business.sql
-- （空库初始化）三轨逐字一致；测试轨见 MallDbSchema.java。
--
-- 幂等：建表用 CREATE TABLE IF NOT EXISTS；字典用 NOT EXISTS（api_dict_* 除主键外无唯一键，
-- IGNORE 无键可撞会翻倍）；菜单与角色绑定携显式 ID 用 INSERT IGNORE。
-- 全文无 DROP/TRUNCATE/无 WHERE 的 DELETE 或 UPDATE。
--
-- 本迁移不改任何库存列、不写任何库存流水：S2 支付成功时已完成实销，
-- S3 只推进物理履约与订单状态，库存回库属 S4（流水类型 8）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1) 商城履约任务：一单一任务；收货信息为订单冻结快照
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_fulfillment` (
  `ID`                     bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`            tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`              bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`            varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`              bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`            varchar(14)  NOT NULL COMMENT '更新时间',
  `ORDER_ID`               bigint       NOT NULL COMMENT '商城订单ID（ws_mall_order.ID）；一单一任务由 uk 兜底',
  `ORDER_NO`               varchar(32)  NOT NULL COMMENT '商城订单号快照：三端与本表共用同一编号，履约刻意不另发展示号，避免用户/仓库/配送员各报一个号对不上',
  `USER_ID`                bigint       NOT NULL COMMENT '收货用户ID（ws_user.ID）；分配配送员时据此拒绝自己给自己配送',
  `WAREHOUSE_ID`           bigint       NOT NULL COMMENT '前置仓ID快照（ws_mall_warehouse.ID）；仓库范围判定只吃本列，不回查订单',
  `COURIER_ID`             bigint       COMMENT '配送员ID（ws_courier.ID）；未分配为空，分配 CAS 以 IS NULL 为前置',
  `FULFILL_STATUS`         tinyint      NOT NULL COMMENT '履约状态(1396)：1待拣货 2待打包 3待分配 4待取货 5配送中 6已送达待签收 7已签收',
  `VERSION`                int          NOT NULL COMMENT '乐观锁版本：创建=1，每次状态转换+1；所有转换 WHERE VERSION=期望值',
  `RECEIVER_NAME`          varchar(30)  NOT NULL COMMENT '收货人快照(max30)：取自订单冻结快照，履约期间不回查地址表——地址改了不能改已发出的货',
  `RECEIVER_PHONE`         varchar(11)  NOT NULL COMMENT '收货电话快照(max11)：展示必须脱敏，非本人配送员一律不下发',
  `RECEIVER_REGION`        varchar(100) NOT NULL COMMENT '收货区域快照(max100)',
  `RECEIVER_ADDRESS`       varchar(200) NOT NULL COMMENT '收货详细地址快照(max200)',
  `RECEIVER_DISTRICT_CODE` varchar(6)   NOT NULL COMMENT '收货区县码快照(6位)',
  `PICK_TIME`              varchar(14)  COMMENT '拣货时间（履约开始，订单同事务 2→3）',
  `PACK_TIME`              varchar(14)  COMMENT '打包完成时间',
  `ASSIGN_TIME`            varchar(14)  COMMENT '分配配送员时间',
  `FETCH_TIME`             varchar(14)  COMMENT '配送员取货时间',
  `ARRIVE_TIME`            varchar(14)  COMMENT '送达时间',
  `SIGN_TIME`              varchar(14)  COMMENT '签收时间（服务端生成，非客户端上传；与订单完成时间、轨迹与消息同源。审计事件不在此列——领域事件服务无业务时间入参）',
  `SIGN_METHOD`            tinyint      COMMENT '签收方式(1398)：1本人签收 2他人代收；签收前为空',
  `SIGN_REMARK`            varchar(200) COMMENT '签收备注(max200)：S3 的签收证据为结构化文本，照片证据待对象存储接入后再议',
  `FULFILL_REMARK`         varchar(500) COMMENT '履约备注(max500)',
  PRIMARY KEY (`ID`),
  -- 一单一任务是履约安全键：并发重复建任务必须由库层挡住，不能依赖代码查重。
  -- 刻意不含 DATA_STATUS——任务被误删后重建会绕开唯一性，等于一单两任务。
  UNIQUE KEY `uk_mall_fulfill_order` (`ORDER_ID`),
  INDEX `idx_mall_fulfill_courier_status` (`COURIER_ID`, `FULFILL_STATUS`),
  INDEX `idx_mall_fulfill_wh_status` (`WAREHOUSE_ID`, `FULFILL_STATUS`),
  INDEX `idx_mall_fulfill_order_no` (`ORDER_NO`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城履约任务表（E2E-09 S3）';
-- ----------------------------
-- 2) 商城履约轨迹：节点即状态到达史，幂等键防重复点击重复写
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_fulfillment_trace` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间',
  `FULFILL_ID`          bigint       NOT NULL COMMENT '履约任务ID（ws_mall_fulfillment.ID）',
  `ORDER_NO`            varchar(32)  NOT NULL COMMENT '商城订单号快照：三端时间线按本列对齐',
  `TRACE_NODE`          tinyint      NOT NULL COMMENT '到达节点(1396)：与任务状态同值域，轨迹即状态到达史',
  `ACTOR_TYPE`          tinyint      NOT NULL COMMENT '操作方(1397)：1系统 2前置仓 3配送员 4用户',
  `ACTOR_ID`            bigint       NOT NULL COMMENT '操作人ID；系统动作写 0',
  `SUBJECT_ID`          bigint       COMMENT '节点业务对象ID：分配节点记录被分配配送员ID。与 ACTOR_ID 刻意分列——ACTOR_ID 是「谁做的」（分配节点为仓库操作员），SUBJECT_ID 是「做给谁」；混用会让配送归属无从核验',
  `TRACE_TIME`          varchar(14)  NOT NULL COMMENT '节点时间：与任务时间、订单时间与消息同源（审计事件时间由领域事件服务自行生成，不同源）',
  `TRACE_TEXT`          varchar(200) NOT NULL COMMENT '节点文案(max200)',
  `BIZ_IDEMPOTENCY_KEY` varchar(64)  NOT NULL COMMENT '幂等键：MFT:<orderNo>:<node>。重复点击撞键即跳过，这是"重复推进不得重复写轨迹"的库层保证',
  PRIMARY KEY (`ID`),
  -- 不含 DATA_STATUS：删掉一条轨迹不该让同一节点可以再写一遍
  UNIQUE KEY `uk_mall_ftrace_key` (`BIZ_IDEMPOTENCY_KEY`),
  INDEX `idx_mall_ftrace_fulfill` (`FULFILL_ID`),
  INDEX `idx_mall_ftrace_order_no` (`ORDER_NO`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城履约轨迹表（E2E-09 S3）';
-- ----------------------------
-- 3) 商城配送员前置仓服务范围：让「范围外」成为可判定事实
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_courier_scope` (
  `ID`           bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`  tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0生效 1解除',
  `CREATE_BY`    bigint      NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`  varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`    bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`  varchar(14) NOT NULL COMMENT '更新时间',
  `WAREHOUSE_ID` bigint      NOT NULL COMMENT '前置仓ID（ws_mall_warehouse.ID）',
  `COURIER_ID`   bigint      NOT NULL COMMENT '配送员ID（ws_courier.ID）',
  PRIMARY KEY (`ID`),
  -- 一期 ws_courier 的服务范围是水站集（STATION_IDS）与自由文本 SERVICE_REGION，
  -- 与商城前置仓不是同一套坐标系；按文本猜区域正是 DISTRICT_CODE 那次的教训。
  -- 故商城域自持这张绑定表：配送员服务哪些前置仓是可判定事实，"范围外"因此能被拒绝也能被测试。
  UNIQUE KEY `uk_mall_courier_scope` (`WAREHOUSE_ID`, `COURIER_ID`),
  INDEX `idx_mall_cscope_courier` (`COURIER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城配送员前置仓服务范围表（E2E-09 S3）';
-- ----------------------------
-- 3b) 商城前置仓操作员归属（E2E-09 S3）：让「跨仓操作」成为可判定拒绝
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_warehouse_operator` (
  `ID`           bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`  tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0生效 1解除',
  `CREATE_BY`    bigint      NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`  varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`    bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`  varchar(14) NOT NULL COMMENT '更新时间',
  `WAREHOUSE_ID` bigint      NOT NULL COMMENT '前置仓ID（ws_mall_warehouse.ID）',
  `OPERATOR_ID`  bigint      NOT NULL COMMENT '管理端操作员ID（api_rbac_employee.ID）',
  PRIMARY KEY (`ID`),
  -- 没有这张表，"仓库人员只能处理归属仓库订单"就只是一句文档：
  -- 拣货/打包/分配只会把 operatorId 写进审计，任何已登录的管理端账号都能动别的仓的货。
  UNIQUE KEY `uk_mall_wh_operator` (`WAREHOUSE_ID`, `OPERATOR_ID`),
  INDEX `idx_mall_wh_operator_op` (`OPERATOR_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城前置仓操作员归属表（E2E-09 S3）';

-- ----------------------------
-- 4) 字典：1396 履约任务状态 / 1397 履约操作方 / 1398 签收方式
-- ----------------------------
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.DICT_NAME, s.DICT_TYPE, s.DICT_REMARK FROM (
            SELECT '商城履约任务状态' AS DICT_NAME, '1396' AS DICT_TYPE, '商城前置仓履约与配送签收状态机' AS DICT_REMARK
  UNION ALL SELECT '商城履约操作方', '1397', '商城履约轨迹节点的操作方'
  UNION ALL SELECT '商城签收方式', '1398', '商城订单签收方式'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1396' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '待拣货' AS DICT_LABEL
  UNION ALL SELECT '1396', 2, 2, '待打包'
  UNION ALL SELECT '1396', 3, 3, '待分配'
  UNION ALL SELECT '1396', 4, 4, '待取货'
  UNION ALL SELECT '1396', 5, 5, '配送中'
  UNION ALL SELECT '1396', 6, 6, '已送达待签收'
  UNION ALL SELECT '1396', 7, 7, '已签收'
  UNION ALL SELECT '1397', 1, 1, '系统'
  UNION ALL SELECT '1397', 2, 2, '前置仓'
  UNION ALL SELECT '1397', 3, 3, '配送员'
  UNION ALL SELECT '1397', 4, 4, '用户'
  UNION ALL SELECT '1398', 1, 1, '本人签收'
  UNION ALL SELECT '1398', 2, 2, '他人代收'
  -- 商城消息不塞进「配送」域（那是水配送链的语义），也不塞进「系统」域含糊了事
  UNION ALL SELECT '1312', 6, 6, '商城'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

-- ----------------------------
-- 5) 菜单：商城履约
--    ID/路径/组件与 deploy/mysql/init/03-demo-baseline.sql 逐字一致。
--    1044 是实测空号：S1 用 1036~1039、S2 用 1043，编号区间按模块交错分布，
--    「上一个 +1」推不出空号——R1 那次 1040 撞一期「C端用户」即由此而来。
-- ----------------------------
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`)
VALUES
(1044,'商城履约',2,NULL,1005,6,'fulfillment','/mall/fulfillment',1,NULL,NULL,1,1,0,1,'20260809120000',1,'20260809120000');

INSERT IGNORE INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260809120000',1,'20260809120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` IN (1044)
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

-- ----------------------------
-- 后置不变式：三表存在、字典与菜单就位
-- 履约表刻意不带演示种子：没有真实订单与实销流水的假任务会让订单、库存与履约互相打架，
-- 演示数据反而在说谎；履约数据由隔离环境实际下单并支付后产生。
-- ----------------------------
SELECT
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
    AND table_name IN ('ws_mall_fulfillment','ws_mall_fulfillment_trace',
                       'ws_mall_courier_scope','ws_mall_warehouse_operator')) AS s3_tables_expect_4,
  (SELECT COUNT(*) FROM api_dict_type WHERE DICT_TYPE IN ('1396','1397','1398')) AS dict_types_expect_3,
  (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE IN ('1396','1397','1398')) AS dict_values_expect_13,
  (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE = '1312') AS msg_domain_expect_6,
  (SELECT COUNT(*) FROM api_rbac_menu WHERE MENU_COMPONENT = '/mall/fulfillment') AS menu_expect_1;

SELECT '2026-08-09 迁移完成：E2E-09 S3 履约三表、字典 1396~1398 与商城履约菜单就绪' AS RESULT;

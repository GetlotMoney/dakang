-- ============================================================
-- 【仅限空库】本文件以 DROP TABLE IF EXISTS 开头，只能用于全新空库。
-- 对既有库（主库 3308／验收库 3309／任何已部署环境）执行会静默删光本域全部数据，
-- 无任何提示、不可恢复。既有库一律改走 deploy/mysql/migrations/ 下的非破坏迁移。
-- 详见 AGENTS.md「第 1 步：数据库设计」第 5 条。
-- ============================================================
-- ============================================================
-- 六维达康 · 运维事件域（ws_ops）
-- 表：ws_alarm / ws_work_order / ws_domain_event
-- 字典段：1360 告警类型、1361 告警状态、1362 工单状态、1363 事件类型
-- 需求映射：运维告警工单与巡检闭环 / 故障阻断下单（告警源）/ 滤芯寿命和状态（超时告警）/
--          后端事件模型与 n8n 可订阅白名单 / 消息中心与微信订阅通知事件（事件表为触达源，一期先落表）
-- 闭环设计（吸取物业项目"待整改死状态"教训，状态机必须能走到终态）：
--   告警：1待处理 → 2已转工单 / 3已忽略 / 4自动恢复
--   工单：1待派工 → 2处理中 → 3已完成 → 4已关闭（每个状态都有对应操作接口，不留死状态）
-- ============================================================

-- ----------------------------
-- 告警表（设备离线/故障/TDS超标/滤芯超时/SIM异常/指令超时 统一入口）
-- ----------------------------
DROP TABLE IF EXISTS `ws_alarm`;
CREATE TABLE `ws_alarm` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `DEVICE_ID`        bigint       NOT NULL COMMENT '设备ID',
  `ALARM_TYPE`       tinyint      NOT NULL COMMENT '告警类型(1360)：1设备离线 2故障码 3TDS超标 4滤芯超时 5SIM异常 6指令超时 7出水异常',
  `ALARM_LEVEL`      tinyint      NOT NULL COMMENT '告警等级(1304 复用故障等级)：1提示 2一般 3严重',
  `ALARM_CONTENT`    varchar(500) NOT NULL COMMENT '告警内容(max500)',
  `SOURCE_REF`       varchar(64)  COMMENT '来源引用(max64)：故障码/指令号/遥测ID',
  `ALARM_STATUS`     tinyint      NOT NULL COMMENT '告警状态(1361)：1待处理 2已转工单 3已忽略 4自动恢复',
  `WORK_ORDER_ID`    bigint       COMMENT '转出的工单ID',
  `RECOVER_TIME`     varchar(14)  COMMENT '恢复时间（自动恢复时回填）',
  PRIMARY KEY (`ID`),
  INDEX `idx_alarm_device_status` (`DEVICE_ID`, `ALARM_STATUS`),
  INDEX `idx_alarm_type` (`ALARM_TYPE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备告警表';

-- ----------------------------
-- 运维工单表（告警转工单 / 机主报修 / 后台手工建单 三来源）
-- ----------------------------
DROP TABLE IF EXISTS `ws_work_order`;
CREATE TABLE `ws_work_order` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `ORDER_NO`         varchar(32)  NOT NULL COMMENT '工单号(max32)，系统生成，业务唯一，代码层查重',
  `DEVICE_ID`        bigint       COMMENT '设备ID（可空：非设备类工单）',
  `SOURCE_TYPE`      tinyint      NOT NULL COMMENT '来源(1)：1告警转入 2机主报修 3后台创建',
  `ALARM_ID`         bigint       COMMENT '来源告警ID',
  `ORDER_TITLE`      varchar(100) NOT NULL COMMENT '工单标题(max100)',
  `ORDER_CONTENT`    varchar(1000) COMMENT '问题描述(max1000)',
  `ORDER_PHOTOS`     text         COMMENT '现场照片JSON数组（OSS URL）',
  `ASSIGNEE_ID`      bigint       COMMENT '处理人（api_employee.ID，派工后回填）',
  `ORDER_STATUS`     tinyint      NOT NULL COMMENT '工单状态(1362)：1待派工 2处理中 3已完成 4已关闭',
  `ASSIGN_TIME`      varchar(14)  COMMENT '派工时间',
  `FINISH_TIME`      varchar(14)  COMMENT '完成时间',
  `FINISH_RESULT`    varchar(500) COMMENT '处理结果(max500)',
  PRIMARY KEY (`ID`),
  INDEX `idx_wo_no` (`ORDER_NO`),
  INDEX `idx_wo_device` (`DEVICE_ID`),
  INDEX `idx_wo_assignee_status` (`ASSIGNEE_ID`, `ORDER_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='运维工单表';

-- ----------------------------
-- 领域事件表（outbox：订单/设备/工单/告警状态变化统一沉淀；
-- n8n 只读订阅白名单事件；后续消息中心/微信订阅通知从此表消费）
-- ----------------------------
DROP TABLE IF EXISTS `ws_domain_event`;
CREATE TABLE `ws_domain_event` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `EVENT_TYPE`       tinyint      NOT NULL COMMENT '事件类型(1363)：1订单状态变化 2设备状态变化 3告警产生 4工单状态变化 5配送节点变化 6支付结果 7分账结果 8指令状态变化',
  `EVENT_KEY`        varchar(64)  NOT NULL COMMENT '业务键(max64)：订单号/设备编号/工单号',
  `EVENT_PAYLOAD`    text         NOT NULL COMMENT '事件快照JSON（旧值/新值/时间）',
  `WHITELIST_FLAG`   tinyint      NOT NULL DEFAULT 1 COMMENT '是否n8n白名单可订阅(1)：1否 2是',
  `CONSUMED_FLAG`    tinyint      NOT NULL DEFAULT 1 COMMENT '是否已被通知消费(1)：1否 2是（消息中心二期用）',
  `BIZ_IDEMPOTENCY_KEY` varchar(64) NULL DEFAULT NULL COMMENT '业务幂等键，仅为需要数据库级幂等的领域事件提供稳定业务键（L2-DB 契约§5.1）',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_domain_event_biz_key` (`BIZ_IDEMPOTENCY_KEY`),
  INDEX `idx_event_type_time` (`EVENT_TYPE`, `CREATE_TIME`),
  INDEX `idx_event_key` (`EVENT_KEY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='领域事件表';

-- ----------------------------
-- 字典
-- ----------------------------
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('告警类型', '1360', '设备告警类型');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1360', 1, 1, '设备离线');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1360', 2, 2, '故障码');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1360', 3, 3, 'TDS超标');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1360', 4, 4, '滤芯超时');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1360', 5, 5, 'SIM异常');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1360', 6, 6, '指令超时');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1360', 7, 7, '出水异常');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('告警状态', '1361', '告警处理状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1361', 1, 1, '待处理');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1361', 2, 2, '已转工单');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1361', 3, 3, '已忽略');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1361', 4, 4, '自动恢复');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('工单状态', '1362', '运维工单状态机');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1362', 1, 1, '待派工');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1362', 2, 2, '处理中');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1362', 3, 3, '已完成');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1362', 4, 4, '已关闭');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('事件类型', '1363', '领域事件类型（n8n白名单订阅源）');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 1, 1, '订单状态变化');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 2, 2, '设备状态变化');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 3, 3, '告警产生');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 4, 4, '工单状态变化');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 5, 5, '配送节点变化');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 6, 6, '支付结果');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 7, 7, '分账结果');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 8, 8, '指令状态变化');

-- ----------------------------
-- 测试数据（一条严重告警已转工单 + 对应处理中工单，演示告警→工单闭环）
-- ----------------------------
INSERT IGNORE INTO `ws_alarm` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DEVICE_ID`, `ALARM_TYPE`, `ALARM_LEVEL`, `ALARM_CONTENT`, `SOURCE_REF`, `ALARM_STATUS`, `WORK_ORDER_ID`, `RECOVER_TIME`) VALUES
(1, 0, 1, '20260710160000', 1, '20260710161000', 2, 2, 3, '设备上报严重故障 E003 流量计异常，已自动阻断下单', 'E003', 2, 1, NULL),
(2, 0, 1, '20260710170000', 1, '20260710170000', 2, 1, 2, '设备心跳超时 10 分钟，判定离线', NULL, 1, NULL, NULL);

INSERT IGNORE INTO `ws_work_order` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `ORDER_NO`, `DEVICE_ID`, `SOURCE_TYPE`, `ALARM_ID`, `ORDER_TITLE`, `ORDER_CONTENT`, `ORDER_PHOTOS`, `ASSIGNEE_ID`, `ORDER_STATUS`, `ASSIGN_TIME`, `FINISH_TIME`, `FINISH_RESULT`) VALUES
(1, 0, 1, '20260710161000', 1, '20260710162000', 'GD20260710161000001', 2, 1, 1, '南湖1号机流量计异常', '告警自动转入：E003 流量计异常，需现场检修', NULL, 1, 2, '20260710162000', NULL, NULL);

-- ----------------------------

-- 菜单与按钮权限统一维护在 deploy/mysql/init/03-demo-baseline.sql；本领域 SQL 只定义表、字典和样例数据。

-- ============================================================
-- 【仅限空库】本文件以 DROP TABLE IF EXISTS 开头，只能用于全新空库。
-- 对既有库（主库 3308／验收库 3309／任何已部署环境）执行会静默删光本域全部数据，
-- 无任何提示、不可恢复。既有库一律改走 deploy/mysql/migrations/ 下的非破坏迁移。
-- 详见 AGENTS.md「第 1 步：数据库设计」第 5 条。
-- ============================================================
-- ============================================================
-- 六维达康 · 设备域（ws_device）
-- 表：ws_device / ws_device_outlet / ws_qrcode / ws_fault_dict / ws_device_msg / ws_device_telemetry
-- 字典段：1300 设备在线状态、1301 设备运行状态、1303 二维码类型、1304 故障等级、
--        1310 上行消息类型、1311 消息处理状态、1383 滤芯状态（编号已注册 ApiEnum.DictType）
-- 需求映射：设备唯一编号和基础档案 / 设备心跳和在线状态 / 设备运行状态上报 / 故障码字典 /
--          设备二维码万能码 / 断网补传和消息去重 / TDS与水质展示 / 滤芯寿命和状态
-- ============================================================

-- ----------------------------
-- 设备表
-- ----------------------------
DROP TABLE IF EXISTS `ws_device`;
CREATE TABLE `ws_device` (
  `ID`                 bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`        tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`          bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`        varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`          bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`        varchar(14)  NOT NULL COMMENT '更新时间',
  `DEVICE_NO`          varchar(50)  NOT NULL COMMENT '设备唯一编号(max50)，MQTT clientId 与业务识别主键',
  `DEVICE_NAME`        varchar(50)  NOT NULL COMMENT '设备名称(max50)',
  `DEVICE_MODEL`       varchar(50)  NOT NULL COMMENT '设备型号(max50)',
  `STATION_ID`         bigint       NOT NULL COMMENT '所属水站ID',
  `OWNER_USER_ID`      bigint       COMMENT '机主用户ID（机主端数据范围过滤依据）',
  `FIRMWARE_VERSION`   varchar(50)  COMMENT '固件版本(max50)',
  `SIM_ICCID`          varchar(50)  COMMENT 'SIM卡ICCID(max50)',
  `SIM_CARRIER`        varchar(20)  COMMENT 'SIM运营商(max20)',
  `ONLINE_STATUS`      tinyint      NOT NULL COMMENT '在线状态(1300)：1在线 2离线 3未激活',
  `RUN_STATUS`         tinyint      NOT NULL COMMENT '运行状态(1301)：1空闲 2出水中 3故障 4维护中 5锁机',
  `LAST_HEARTBEAT`     varchar(14)  COMMENT '最后心跳时间',
  `LAST_FAULT_CODE`    varchar(20)  COMMENT '最近故障码(max20)',
  `SIGNAL_STRENGTH`    int          COMMENT '信号强度(dBm，负值)',
  `DEVICE_REMARK`      varchar(500) COMMENT '备注(max500)',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：设备编号是 MQTT 身份与订单归属的根，并发注册重复将造成
  -- 指令错发与账务归属错乱，必须由数据库层兜底，代码层查重仅作为友好提示。
  UNIQUE INDEX `uk_device_no` (`DEVICE_NO`),
  INDEX `idx_device_station` (`STATION_ID`),
  INDEX `idx_device_owner` (`OWNER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备表';

-- ----------------------------
-- 设备出水口表（一台设备多个出水口，不同水种）
-- ----------------------------
DROP TABLE IF EXISTS `ws_device_outlet`;
CREATE TABLE `ws_device_outlet` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `DEVICE_ID`        bigint       NOT NULL COMMENT '所属设备ID',
  `OUTLET_NO`        tinyint      NOT NULL COMMENT '出水口编号（设备内序号 1/2/3...）',
  `WATER_TYPE`       varchar(20)  NOT NULL COMMENT '水种名称(max20)，如 纯净水/矿物质水',
  `OUTLET_PRICE`     varchar(11)  NOT NULL COMMENT '单价(分)，每升价格',
  `OUTLET_STATUS`    tinyint      NOT NULL COMMENT '状态(10)：1正常 2禁用',
  PRIMARY KEY (`ID`),
  INDEX `idx_outlet_device` (`DEVICE_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备出水口表';

-- ----------------------------
-- 设备二维码表（新版/旧版/万能码 → 设备/出水口映射，扫码入口统一解析）
-- ----------------------------
DROP TABLE IF EXISTS `ws_qrcode`;
CREATE TABLE `ws_qrcode` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `QRCODE_CONTENT`   varchar(200) NOT NULL COMMENT '码内容(max200)，扫码原文，业务唯一，代码层查重',
  `QRCODE_TYPE`      tinyint      NOT NULL COMMENT '类型(1303)：1新版设备码 2旧版设备码 3万能码',
  `DEVICE_ID`        bigint       COMMENT '绑定设备ID（万能码可空，扫码后选设备）',
  `OUTLET_ID`        bigint       COMMENT '绑定出水口ID（可空，空则扫码后选口）',
  `QRCODE_STATUS`    tinyint      NOT NULL COMMENT '状态(10)：1正常 2禁用',
  PRIMARY KEY (`ID`),
  INDEX `idx_qrcode_content` (`QRCODE_CONTENT`),
  INDEX `idx_qrcode_device` (`DEVICE_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备二维码表';

-- ----------------------------
-- 故障码字典表（硬件厂提供：故障码/含义/等级/处理建议/是否阻断下单）
-- ----------------------------
DROP TABLE IF EXISTS `ws_fault_dict`;
CREATE TABLE `ws_fault_dict` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `FAULT_CODE`       varchar(20)  NOT NULL COMMENT '故障码(max20)，硬件协议原值，业务唯一，代码层查重',
  `FAULT_NAME`       varchar(50)  NOT NULL COMMENT '故障名称(max50)',
  `FAULT_LEVEL`      tinyint      NOT NULL COMMENT '故障等级(1304)：1提示 2一般 3严重',
  `BLOCK_ORDER_FLAG` tinyint      NOT NULL COMMENT '是否阻断下单(1)：1否 2是（严重故障阻断用户取水）',
  `FAULT_ADVICE`     varchar(500) COMMENT '处理建议(max500)',
  PRIMARY KEY (`ID`),
  INDEX `idx_fault_code` (`FAULT_CODE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='故障码字典表';

-- ----------------------------
-- 设备上行消息表（心跳外的事件/回执/水量回传均落此表；MSG_ID 去重支撑断网补传）
-- ----------------------------
DROP TABLE IF EXISTS `ws_device_msg`;
CREATE TABLE `ws_device_msg` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `MSG_ID`           varchar(64)  NOT NULL COMMENT '设备侧消息ID(max64)，断网补传去重键',
  `DEVICE_ID`        bigint       NOT NULL COMMENT '设备ID',
  `MSG_TYPE`         tinyint      NOT NULL COMMENT '消息类型(1310)：1状态上报 2指令回执 3水量回传 4故障事件 5补传消息',
  `MSG_TOPIC`        varchar(100) NOT NULL COMMENT '来源MQTT主题(max100)',
  `MSG_PAYLOAD`      text         COMMENT '消息原文JSON',
  `DEVICE_TIME`      varchar(14)  COMMENT '设备侧发生时间（补传时早于落库时间）',
  `HANDLE_STATUS`    tinyint      NOT NULL COMMENT '处理状态(1311)：1待处理 2已处理 3处理失败 4重复丢弃',
  `HANDLE_REMARK`    varchar(500) COMMENT '处理备注(max500)',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：MSG_ID 去重是“断网补传不得重复扣减/重复出水”的最后防线，
  -- 补传与实时消息可能并发到达，必须由数据库层兜底。
  UNIQUE INDEX `uk_msg_id` (`MSG_ID`),
  INDEX `idx_msg_device_type` (`DEVICE_ID`, `MSG_TYPE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备上行消息表';

-- ----------------------------
-- 设备遥测表（TDS/水温/滤芯/信号周期上报，供水质展示与滤芯预警）
-- ----------------------------
DROP TABLE IF EXISTS `ws_device_telemetry`;
CREATE TABLE `ws_device_telemetry` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `DEVICE_ID`        bigint       NOT NULL COMMENT '设备ID',
  `TDS_VALUE`        int          COMMENT '出水TDS值(ppm)',
  `RAW_TDS_VALUE`    int          COMMENT '原水TDS值(ppm)',
  `WATER_TEMP`       int          COMMENT '水温(摄氏度)',
  `FILTER_LIFE_JSON` text         COMMENT '滤芯寿命JSON：[{"no":1,"restDay":30,"status":1}]，status见字典1383',
  `SIGNAL_STRENGTH`  int          COMMENT '信号强度(dBm)',
  `REPORT_TIME`      varchar(14)  NOT NULL COMMENT '上报时间',
  PRIMARY KEY (`ID`),
  INDEX `idx_telemetry_device_time` (`DEVICE_ID`, `REPORT_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备遥测表';

-- ----------------------------
-- 字典
-- ----------------------------
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('设备在线状态', '1300', '设备心跳判定的在线状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1300', 1, 1, '在线');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1300', 2, 2, '离线');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1300', 3, 3, '未激活');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('设备运行状态', '1301', '设备运行状态上报');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1301', 1, 1, '空闲');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1301', 2, 2, '出水中');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1301', 3, 3, '故障');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1301', 4, 4, '维护中');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1301', 5, 5, '锁机');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('二维码类型', '1303', '设备二维码/万能码类型');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1303', 1, 1, '新版设备码');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1303', 2, 2, '旧版设备码');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1303', 3, 3, '万能码');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('故障等级', '1304', '故障码等级');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1304', 1, 1, '提示');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1304', 2, 2, '一般');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1304', 3, 3, '严重');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('上行消息类型', '1310', '设备上行MQTT消息类型');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1310', 1, 1, '状态上报');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1310', 2, 2, '指令回执');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1310', 3, 3, '水量回传');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1310', 4, 4, '故障事件');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1310', 5, 5, '补传消息');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('消息处理状态', '1311', '设备上行消息处理状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1311', 1, 1, '待处理');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1311', 2, 2, '已处理');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1311', 3, 3, '处理失败');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1311', 4, 4, '重复丢弃');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('滤芯状态', '1383', '滤芯寿命状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1383', 1, 1, '正常');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1383', 2, 2, '即将到期');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1383', 3, 3, '已超期');

-- ----------------------------
-- 测试数据（在线/离线各一台 + 出水口 + 遥测快照 + 设备码 + 故障码样例）
-- ----------------------------
INSERT IGNORE INTO `ws_device` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DEVICE_NO`, `DEVICE_NAME`, `DEVICE_MODEL`, `STATION_ID`, `OWNER_USER_ID`, `FIRMWARE_VERSION`, `SIM_ICCID`, `SIM_CARRIER`, `ONLINE_STATUS`, `RUN_STATUS`, `LAST_HEARTBEAT`, `LAST_FAULT_CODE`, `SIGNAL_STRENGTH`, `DEVICE_REMARK`) VALUES
(1, 0, 1, '20260710120000', 1, '20260714125500', 'DK-DEV-0001', '光谷1号机', 'DK-W800', 1, 4, 'v1.0.0', '898604202607100001', '中国移动', 1, 1, '20260714125500', NULL, -68, '光谷片区主力设备'),
(2, 0, 1, '20260710120000', 1, '20260714100000', 'DK-DEV-0002', '南湖1号机', 'DK-W800', 2, 4, 'v1.0.0', '898604202607100002', '中国移动', 2, 3, '20260714080000', 'E003', -112, 'E003 流量计异常，故障离线并阻断下单');

INSERT IGNORE INTO `ws_device_outlet` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DEVICE_ID`, `OUTLET_NO`, `WATER_TYPE`, `OUTLET_PRICE`, `OUTLET_STATUS`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', 1, 1, '纯净水', '20', 1),
(2, 0, 1, '20260710120000', 1, '20260710120000', 1, 2, '矿物质水', '30', 1),
(3, 0, 1, '20260710120000', 1, '20260710120000', 2, 1, '纯净水', '20', 1);

INSERT IGNORE INTO `ws_qrcode` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `QRCODE_CONTENT`, `QRCODE_TYPE`, `DEVICE_ID`, `OUTLET_ID`, `QRCODE_STATUS`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', 'DK-QR-DEV0001-O1', 1, 1, 1, 1),
(2, 0, 1, '20260710120000', 1, '20260710120000', 'DK-QR-DEV0001-O2', 1, 1, 2, 1),
(3, 0, 1, '20260710120000', 1, '20260710120000', 'DK-QR-UNIVERSAL-001', 3, NULL, NULL, 1),
(4, 0, 1, '20260710120000', 1, '20260710120000', 'DK-QR-DEV0002-O1', 1, 2, 3, 1),
-- 常驻禁用码（状态2）：供 L1a QR_EXPIRED 拒绝分支真链验收（H2-B2），免临时 UPDATE 污染种子
(5, 0, 1, '20260710120000', 1, '20260710120000', 'DK-QR-DISABLED-001', 1, 1, 1, 2);

INSERT IGNORE INTO `ws_device_telemetry` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DEVICE_ID`, `TDS_VALUE`, `RAW_TDS_VALUE`, `WATER_TEMP`, `FILTER_LIFE_JSON`, `SIGNAL_STRENGTH`, `REPORT_TIME`) VALUES
(1, 0, 1, '20260714125500', 1, '20260714125500', 1, 42, 178, 24, '[{"no":1,"restDay":120,"status":1},{"no":2,"restDay":12,"status":2}]', -68, '20260714125500'),
(2, 0, 1, '20260710083000', 1, '20260710083000', 2, 58, 185, 23, '[{"no":1,"restDay":70,"status":1},{"no":2,"restDay":0,"status":3}]', -112, '20260710083000');

INSERT IGNORE INTO `ws_fault_dict` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `FAULT_CODE`, `FAULT_NAME`, `FAULT_LEVEL`, `BLOCK_ORDER_FLAG`, `FAULT_ADVICE`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', 'E001', '缺水保护', 3, 2, '检查进水阀与水源，恢复后自动解除'),
(2, 0, 1, '20260710120000', 1, '20260710120000', 'E002', '滤芯超期', 2, 1, '安排换芯工单，不阻断取水'),
(3, 0, 1, '20260710120000', 1, '20260710120000', 'E003', '流量计异常', 3, 2, '停机检修，联系硬件厂'),
(4, 0, 1, '20260710120000', 1, '20260710120000', 'E004', 'TDS超标', 3, 2, '立即停售并派工检测水质');

-- ----------------------------

-- 菜单与按钮权限统一维护在 deploy/mysql/init/03-demo-baseline.sql；本领域 SQL 只定义表、字典和样例数据。

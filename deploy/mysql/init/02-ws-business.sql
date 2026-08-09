-- 六维达康 · 业务域初始化（由 server/sql/ws_*.sql 合并生成，修改请改源文件后重新合并）
SET NAMES utf8mb4;
USE dakang;
-- ============================================================
-- 六维达康 · 水站域（ws_station）
-- 依赖：共用底座表 api_dict_type / api_dict_data / api_rbac_menu（库内已存在）
-- 执行：mysql -h127.0.0.1 -P3308 -uroot -p"$DAKANG_DB_PASSWORD" dakang < ws_station.sql
-- 口令不入库：先 `set -a; . .env; set +a` 导出仓库根 .env 的 DAKANG_DB_PASSWORD（模板见 .env.example）
-- 需求映射：需求池「水站管理」（P0/一期必需/MVP:是）
-- ============================================================

-- ----------------------------
-- 水站表
-- ----------------------------
DROP TABLE IF EXISTS `ws_station`;
CREATE TABLE `ws_station` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `STATION_NAME`     varchar(50)  NOT NULL COMMENT '水站名称(max50)',
  `STATION_CODE`     varchar(50)  NOT NULL COMMENT '水站编码(max50)，业务唯一，代码层查重',
  `STATION_REGION`   varchar(50)  NOT NULL COMMENT '所属区域(max50)',
  `STATION_ADDRESS`  varchar(200) NOT NULL COMMENT '详细地址(max200)',
  `STATION_LNG`      varchar(20)  COMMENT '经度(max20)',
  `STATION_LAT`      varchar(20)  COMMENT '纬度(max20)',
  `STATION_STATUS`   tinyint      NOT NULL COMMENT '状态(10)：1正常 2禁用',
  `OWNER_USER_ID`    bigint       COMMENT '机主用户ID（ws_user.ID，机主端数据范围过滤依据）',
  `CHANNEL_USER_ID`  bigint       COMMENT '渠道用户ID（一期仅归属预留，不做渠道端）',
  `STATION_REMARK`   varchar(500) COMMENT '备注(max500)',
  PRIMARY KEY (`ID`),
  INDEX `idx_station_region` (`STATION_REGION`),
  INDEX `idx_station_owner` (`OWNER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='水站表';

-- ----------------------------
-- 测试数据（贴近联调场景：两个水站，一个绑机主一个未绑）
-- ----------------------------
INSERT IGNORE INTO `ws_station` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `STATION_NAME`, `STATION_CODE`, `STATION_REGION`, `STATION_ADDRESS`, `STATION_LNG`, `STATION_LAT`, `STATION_STATUS`, `OWNER_USER_ID`, `CHANNEL_USER_ID`, `STATION_REMARK`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', '光谷软件园水站', 'WS-WH-001', '武汉东湖高新区', '光谷软件园 A1 栋一楼大厅', '114.4276', '30.4586', 1, NULL, NULL, '光谷片区主力水站'),
(2, 0, 1, '20260710120000', 1, '20260710120000', '南湖社区水站', 'WS-WH-002', '武汉洪山区', '南湖佰港城北门', '114.3355', '30.4899', 1, NULL, NULL, '第二水站，验证跨站授权范围拦截');

-- ----------------------------
-- 菜单与权限统一由 03-demo-baseline.sql 建立
-- ----------------------------





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
  `SIM_STATUS`         tinyint      COMMENT 'SIM状态(1368)：1正常 2未激活 3欠费 4停用；由档案维护或模拟器上报，真实运营商查询未接入',
  `SIM_EXPIRE_TIME`    varchar(14)  COMMENT 'SIM到期时间；到期临近由告警引擎产SIM异常告警',
  `ONLINE_STATUS`      tinyint      NOT NULL COMMENT '在线状态(1300)：1在线 2离线 3未激活',
  `RUN_STATUS`         tinyint      NOT NULL COMMENT '运行状态(1301)：1空闲 2出水中 3故障 4维护中 5锁机',
  `LAST_HEARTBEAT`     varchar(14)  COMMENT '最后心跳时间',
  `LAST_FAULT_CODE`    varchar(20)  COMMENT '最近故障码(max20)',
  `LAST_STATUS_DEVICE_TIME` varchar(14) COMMENT '最近一次已应用状态报文的设备时间；乱序补传不得覆盖当前状态',
  `SIGNAL_STRENGTH`    int          COMMENT '信号强度(dBm，负值)',
  `DEVICE_REMARK`      varchar(500) COMMENT '备注(max500)',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：设备编号是 MQTT 身份与订单归属的根，并发注册重复将造成
  -- 数据库唯一约束保证设备编号一致性，服务层查重用于返回明确的业务错误。
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
  -- 唯一约束防止补传消息与实时消息并发写入产生重复记录。
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
-- 菜单与权限统一由 03-demo-baseline.sql 建立
-- ----------------------------





-- ============================================================
-- 六维达康 · 指令域（ws_command）
-- 表：ws_command
-- 字典段：1320 指令类型、1321 指令状态（编号需同步注册 ApiEnum.DictType）
-- 需求映射：平台下发开始出水指令 / 平台下发停止出水指令 / 指令接收回执 /
--          指令执行结果 / 出水不足超量异常 / 设备中控运营控制中心（远程指令）
-- 状态机：1待下发 → 2已下发 → 3已回执 → 4执行成功/5执行失败/7部分完成
--        2/3 停留超时由定时任务翻转为 6超时（扫描间隔与超时阈值在代码层配置）
-- ============================================================

-- ----------------------------
-- 设备指令表
-- ----------------------------
DROP TABLE IF EXISTS `ws_command`;
CREATE TABLE `ws_command` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `CMD_NO`           varchar(64)  NOT NULL COMMENT '平台指令号(max64)，下发携带、回执按此关联',
  `DEVICE_ID`        bigint       NOT NULL COMMENT '目标设备ID',
  `ORDER_ID`         bigint       COMMENT '关联订单ID（出水类指令必填，查询/锁机类为空）',
  `CMD_TYPE`         tinyint      NOT NULL COMMENT '指令类型(1320)：1开始出水 2停止出水 3查询状态 4锁机 5解锁 6参数同步 7重启 8价格同步（平台内部命令，设备模拟器支持，厂商映射待外部协议确认）',
  `CMD_PAYLOAD`      text         COMMENT '下发报文JSON（出水口/水种/计划水量等）',
  `CMD_STATUS`       tinyint      NOT NULL COMMENT '指令状态(1321)：1待下发 2已下发 3已回执 4执行成功 5执行失败 6超时 7部分完成',
  `SENT_TIME`        varchar(14)  COMMENT '下发时间',
  `ACK_TIME`         varchar(14)  COMMENT '设备回执时间',
  `FINISH_TIME`      varchar(14)  COMMENT '终态时间（成功/失败/超时/部分完成）',
  `RESULT_PAYLOAD`   text         COMMENT '执行结果报文JSON（实际水量等，异常补偿依据）',
  `FAIL_REASON`      varchar(500) COMMENT '失败/超时原因(max500)',
  `RETRY_COUNT`      tinyint      NOT NULL DEFAULT 0 COMMENT '重试次数',
  `BATCH_ID`         bigint       COMMENT '批量任务ID(ws_command_batch.ID)；单发指令为NULL',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：CMD_NO 是设备回执匹配与重复下发防护的根，
  -- 唯一约束防止回执与补传并发到达时重复写入同一指令。
  UNIQUE INDEX `uk_cmd_no` (`CMD_NO`),
  -- 同批次同设备只允许一条子指令：批量任务重放/并发展开时撞键回滚而不是重复下发。
  -- MySQL 可空唯一：BATCH_ID 为 NULL 的单发指令不参与约束。
  UNIQUE INDEX `uk_cmd_batch_device` (`BATCH_ID`, `DEVICE_ID`),
  INDEX `idx_cmd_device_status` (`DEVICE_ID`, `CMD_STATUS`),
  INDEX `idx_cmd_order` (`ORDER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备指令表';

-- ----------------------------
-- 批量指令任务表（E2E-05 REQ-063：一批一行，子指令逐设备落 ws_command 并回指 BATCH_ID）。
-- 目标集合由后端按权限与数据范围重新解析（绝不采信前端提交的设备清单），解析结果冻结进
-- SCOPE_SNAPSHOT；聚合数量由子指令终态回写驱动，单设备失败绝不伪装整批成功。
-- ----------------------------
DROP TABLE IF EXISTS `ws_command_batch`;
CREATE TABLE `ws_command_batch` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID（api_employee.ID，发起批量的运营）',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `BATCH_NO`         varchar(32)  NOT NULL COMMENT '批次号(max32)，系统生成，三端贯穿',
  `SCOPE_TYPE`       tinyint      NOT NULL COMMENT '范围类型(1366)：1指定设备 2指定水站 3全部设备',
  `SCOPE_SNAPSHOT`   text         NOT NULL COMMENT '目标快照JSON：{deviceIds:[...],stationId?}——confirm 时后端解析结果冻结，事后审计只认它',
  `CMD_TYPE`         tinyint      NOT NULL COMMENT '指令类型(1320)：批量只允许 3查询 4锁机 5解锁 6参数同步 7重启 8价格同步',
  `CMD_PAYLOAD`      text         COMMENT '参数本体JSON（参数同步/价格同步携带）',
  `PARAM_DIGEST`     char(64)     NOT NULL COMMENT '命令类型+参数摘要 sha256：operationTicket 绑定与 confirm 一致性校验依据',
  `TOTAL_COUNT`      int          NOT NULL COMMENT '子指令总数（=目标设备数）',
  `SUCCESS_COUNT`    int          NOT NULL DEFAULT 0 COMMENT '成功数（子指令终态回写累计）',
  `FAIL_COUNT`       int          NOT NULL DEFAULT 0 COMMENT '失败数（含设备拒绝/执行失败）',
  `TIMEOUT_COUNT`    int          NOT NULL DEFAULT 0 COMMENT '超时数',
  `BATCH_STATUS`     tinyint      NOT NULL COMMENT '聚合状态(1367)：1处理中 2全部成功 3部分成功 4全部失败',
  `FINISH_TIME`      varchar(14)  COMMENT '聚合终态时间（全部子指令达终态时回填）',
  `VERSION`          int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本；聚合数量与状态经 CAS 回写',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_cbatch_no` (`BATCH_NO`),
  INDEX `idx_cbatch_status` (`BATCH_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='批量指令任务表（E2E-05）';

-- ----------------------------
-- 设备参数定义注册表（REQ-213-S1）
-- 单调收紧语义：本表可以是空的。未登记的参数键仍可下发（保持 E2E-05 已验收的
-- 参数同步能力不被打断），但一旦某个键在此登记，平台立刻对它施加类型/值域/单位校验。
-- 登记只会更严、永不更松，因此空表 == 今天的行为，不构成回归。
-- 业务参数键（限额/冲洗/温控等）在厂家答复 V-12.3 之前一条都不灌——
-- 平台不发明设备参数，发一个厂家不认识的键属于不可预期行为。
-- ----------------------------
DROP TABLE IF EXISTS `ws_device_param_def`;
CREATE TABLE `ws_device_param_def` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `CMD_TYPE`         tinyint      NOT NULL COMMENT '所属指令类型(1320)：6参数同步 8价格同步',
  `PARAM_KEY`        varchar(32)  COLLATE utf8mb4_bin NOT NULL COMMENT '参数键名，须匹配 ^[A-Za-z][A-Za-z0-9_]{0,31}$；列级 utf8mb4_bin：表默认 ci 会让唯一键把 mode/MODE 折叠成同一行，而 Java 侧 registry 查找大小写敏感，两者不同构即产生「改个大小写就绕过已登记键校验、回写却落到原行」',
  `PARAM_NAME`       varchar(50)  NOT NULL COMMENT '中文名（运营可读）',
  `VALUE_TYPE`       tinyint      NOT NULL COMMENT '值类型：1整数 2小数 3字符串 4布尔',
  `VALUE_UNIT`       varchar(16)  NULL COMMENT '单位（毫升/秒/摄氏度/分…），无单位留空',
  `VALUE_MIN`        varchar(32)  NULL COMMENT '数值下限（含），非数值类型留空',
  `VALUE_MAX`        varchar(32)  NULL COMMENT '数值上限（含），非数值类型留空',
  `VALUE_ENUM`       varchar(255) NULL COMMENT '允许值枚举，逗号分隔；留空表示不限枚举',
  `DEF_STATUS`       tinyint      NOT NULL COMMENT '状态：1启用（施加校验） 2停用（视同未登记）',
  `DEF_REMARK`       varchar(255) NULL COMMENT '备注：来源依据（厂家文档/协议版本），不得凭空登记',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_param_def_key` (`CMD_TYPE`, `PARAM_KEY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备参数定义注册表（REQ-213）';

-- ----------------------------
-- 设备参数快照表（REQ-213-S1）
-- 回答「这台设备当前参数是什么」。只在 result 成功（非 partial、非失败）后回写，
-- 因为 ACK 只代表受理、不代表设备真的改了参数——否则平台会显示"已同步"而设备没改。
-- 值取自平台下发的 CMD_PAYLOAD（我们发了什么），不取 RESULT_PAYLOAD（设备回什么由厂家定，未确认）。
-- SOURCE_CMD_NO 既是溯源锚，也是幂等闸：同一条指令的重复 result 不得再次抬升版本。
-- ----------------------------
DROP TABLE IF EXISTS `ws_device_param`;
CREATE TABLE `ws_device_param` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `DEVICE_ID`        bigint       NOT NULL COMMENT '设备ID',
  `PARAM_KEY`        varchar(32)  COLLATE utf8mb4_bin NOT NULL COMMENT '参数键名（列级 utf8mb4_bin，与 Java 大小写敏感口径一致）',
  `PARAM_VALUE`      varchar(64)  NOT NULL COMMENT '平台侧认为设备当前生效的值（标量原文）',
  `PARAM_VERSION`    int          NOT NULL DEFAULT 1 COMMENT '该键的同步版本，每次成功回写 +1',
  `SOURCE_CMD_NO`    varchar(40)  NOT NULL COMMENT '写入本值的指令号（溯源用）',
  `SOURCE_CMD_ID`    bigint       NOT NULL COMMENT '写入本值的指令ID（ws_command.ID）。单调守卫：自增即平台下发顺序，只有更大的 ID 才能覆盖——设备断网补传或消息重投会让更早指令的 result 后到，按到达顺序写会把新值覆盖成旧值且版本号还 +1。绝不用设备时钟排序',
  `SYNC_TIME`        varchar(14)  NOT NULL COMMENT '设备回报成功的时间（result 的 finishTs）',
  `REGISTERED_FLAG`  tinyint      NOT NULL DEFAULT 0 COMMENT '写入时该键是否已在定义表登记：0未登记 1已登记',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_device_param` (`DEVICE_ID`, `PARAM_KEY`),
  INDEX `idx_device_param_cmd` (`SOURCE_CMD_NO`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备参数快照表（REQ-213）';

-- 参数定义初始灌值：只灌有文档来源的键，绝不发明设备参数。
-- priceVersion 是当前唯一有据可查的：docs/mqtt-topics.md 记载价格同步 payload={priceVersion,...}，
-- 设备模拟器据此在 result 回带；PC 批量下发对话框的示例值为字符串 "PV-20260730-01"，
-- 故 VALUE_TYPE 取 3字符串（按实际形态登记，不按主观期望登记为整数，否则会打断已验收路径）。
-- 参数同步(cmdType=6)的业务键在厂家答复 V-12.3 前保持零登记。
INSERT IGNORE INTO `ws_device_param_def`
  (`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,
   `CMD_TYPE`,`PARAM_KEY`,`PARAM_NAME`,`VALUE_TYPE`,`VALUE_UNIT`,`VALUE_MIN`,`VALUE_MAX`,`VALUE_ENUM`,
   `DEF_STATUS`,`DEF_REMARK`)
VALUES
  (0,1,'20260804000000',1,'20260804000000',
   8,'priceVersion','价格版本号',3,NULL,NULL,NULL,NULL,
   1,'来源：docs/mqtt-topics.md 价格同步报文约定 + 设备模拟器 result 回带；厂商映射待 V-12 确认');

-- ----------------------------
-- 字典
-- ----------------------------
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('指令类型', '1320', '平台下发设备指令类型');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1320', 1, 1, '开始出水');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1320', 2, 2, '停止出水');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1320', 3, 3, '查询状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1320', 4, 4, '锁机');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1320', 5, 5, '解锁');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1320', 6, 6, '参数同步');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1320', 7, 7, '重启');
-- 8价格同步：平台内部命令类型；设备模拟器支持，厂商映射待外部协议确认（E2E-05 冻结口径 3.4）
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1320', 8, 8, '价格同步');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('指令状态', '1321', '设备指令执行状态机');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1321', 1, 1, '待下发');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1321', 2, 2, '已下发');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1321', 3, 3, '已回执');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1321', 4, 4, '执行成功');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1321', 5, 5, '执行失败');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1321', 6, 6, '超时');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1321', 7, 7, '部分完成');

-- ----------------------------
-- 测试数据（覆盖状态机各态，供前端指令列表/详情开发；联调时由模拟器产生真实数据）
-- ----------------------------
INSERT IGNORE INTO `ws_command` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `CMD_NO`, `DEVICE_ID`, `ORDER_ID`, `CMD_TYPE`, `CMD_PAYLOAD`, `CMD_STATUS`, `SENT_TIME`, `ACK_TIME`, `FINISH_TIME`, `RESULT_PAYLOAD`, `FAIL_REASON`, `RETRY_COUNT`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120100', 'CMD-20260710-0001', 1, NULL, 3, '{}', 4, '20260710120000', '20260710120030', '20260710120100', '{"onlineStatus":1,"runStatus":1}', NULL, 0),
(2, 0, 1, '20260710121000', 1, '20260710121200', 'CMD-20260710-0002', 1, NULL, 6, '{"price":{"outlet1":20}}', 4, '20260710121000', '20260710121030', '20260710121200', '{"applied":true}', NULL, 0),
(3, 0, 1, '20260710122000', 1, '20260710122500', 'CMD-20260710-0003', 2, NULL, 4, '{}', 6, '20260710122000', NULL, '20260710122500', NULL, '设备离线，回执超时', 1),
-- 指令 4 为订单 1（WO20260710130000001，已完成）的取水指令：共键 ORDER_ID=1 且状态/时间与订单闭环对齐，
-- 不留"完成订单挂无主/未回执指令"的矛盾种子（2026-07-20 收口轮 P0-1；孤立异常样例由指令 3 承担）。
(4, 0, 1, '20260710130000', 1, '20260710130500', 'CMD-20260710-0004', 1, 1, 1, '{"outletNo":1,"waterType":"纯净水","planMl":5000,"orderNo":"WO20260710130000001"}', 4, '20260710130000', '20260710130030', '20260710130500', '{"actualMl":5000}', NULL, 0);

-- ----------------------------
-- 菜单与权限统一由 03-demo-baseline.sql 建立
-- ----------------------------

-- ============================================================
-- 六维达康 · 用户卡券域（ws_user_card）
-- 表：ws_package / ws_card / ws_card_member
-- 字典段：1330 套餐状态、1331 卡类型、1332 卡状态、1333 成员授权状态
-- 需求映射：水卡余额与权益校验 / 购卡充值 / 一卡多人授权 /
--          套餐兑换汇率、价格快照与退款折算 / 实体水卡刷卡在线授权（一期预留字段）/
--          设备机组与水卡授权范围模型（SCOPE_JSON 预留）
-- 金额说明：金额一律存"分"（bigint）；水量一律存"毫升"（bigint），避免浮点误差
-- C 端用户主体：复用底座 ws_user（客户态 KH_USER），本域不建用户表
-- ============================================================

-- ----------------------------
-- 套餐表（购卡/充值的商品定义；卡与流水保留套餐快照）
-- ----------------------------
DROP TABLE IF EXISTS `ws_package`;
CREATE TABLE `ws_package` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `PACKAGE_NAME`     varchar(50)  NOT NULL COMMENT '套餐名称(max50)',
  `PAY_AMOUNT`       bigint       NOT NULL COMMENT '售价(分)',
  `WATER_ML`         bigint       NOT NULL COMMENT '兑换水量(毫升)，0=纯余额充值套餐',
  `BONUS_AMOUNT`     bigint       NOT NULL DEFAULT 0 COMMENT '赠送余额(分)',
  `UNIT_PRICE_SNAP`  varchar(20)  NOT NULL COMMENT '折算单价快照(分/升,字符串保留两位)，退款折算与对账依据',
  `SCOPE_JSON`       text         COMMENT '可用范围JSON（水站/机组/设备白名单；空=未配置并默认拒绝；全场需显式scopeType=all）',
  `EXPIRE_DAYS`      int          COMMENT '有效期(天)，空=永久',
  `PACKAGE_STATUS`   tinyint      NOT NULL COMMENT '状态(1330)：1在售 2下架',
  `PACKAGE_REMARK`   varchar(500) COMMENT '备注(max500)',
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='水卡套餐表';

-- ----------------------------
-- 水卡表（虚拟卡为主；实体卡通过 CARD_NO/ENTITY_FLAG 支持，在线授权一期预留）
-- ----------------------------
DROP TABLE IF EXISTS `ws_card`;
CREATE TABLE `ws_card` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `CARD_NO`          varchar(32)  NOT NULL COMMENT '卡号(max32)，虚拟卡系统生成/实体卡取卡面编号',
  `CARD_TYPE`        tinyint      NOT NULL COMMENT '卡类型(1331)：1虚拟卡 2实体卡',
  `USER_ID`          bigint       NOT NULL COMMENT '持卡用户ID（ws_user.ID，主卡人）',
  `BALANCE_AMOUNT`   bigint       NOT NULL DEFAULT 0 COMMENT '余额(分)。扣减必须原子UPDATE并写ws_wallet_flow流水',
  `BALANCE_ML`       bigint       NOT NULL DEFAULT 0 COMMENT '剩余水量(毫升)。扣减规则同上',
  `PACKAGE_ID`       bigint       COMMENT '最近购买套餐ID',
  `PACKAGE_SNAP`     text         COMMENT '套餐快照JSON（名称/售价/水量/单价），退款折算与申诉对账依据',
  `SCOPE_JSON`       text         COMMENT '可用范围JSON（继承套餐，可后台改；空=未配置并默认拒绝）',
  `EXPIRE_TIME`      varchar(14)  COMMENT '到期时间，空=永久',
  `CARD_STATUS`      tinyint      NOT NULL COMMENT '卡状态(1332)：1正常 2冻结 3已过期 4已注销',
  `CARD_REMARK`      varchar(500) COMMENT '备注(max500)',
  `ISSUE_ORDER_ID`   bigint       NULL COMMENT '首次购卡发行订单ID（L2-A3 唯一发行锚点）；L2-A 建卡必写，历史/实体/人工卡为 NULL',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：卡号是取水扣费与实体卡授权的资金主键，
  -- 唯一约束防止并发开卡导致重复卡号与资金归属异常。
  UNIQUE INDEX `uk_card_no` (`CARD_NO`),
  -- L2-A3：同一发行订单至多发出一张卡。RECHARGE:<orderNo> 幂等键只防重复入账，
  -- 防不了"入账回滚后重放又建一张卡"——这道唯一键才是发卡幂等的最后防线。
  UNIQUE INDEX `uk_card_issue_order` (`ISSUE_ORDER_ID`),
  INDEX `idx_card_user` (`USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='水卡表';

-- ----------------------------
-- 水卡成员授权表（一卡多人：主卡人授权家庭/企业成员用卡）
-- ----------------------------
DROP TABLE IF EXISTS `ws_card_member`;
CREATE TABLE `ws_card_member` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `CARD_ID`          bigint       NOT NULL COMMENT '水卡ID',
  `MEMBER_USER_ID`   bigint       NOT NULL COMMENT '被授权用户ID（ws_user.ID）',
  `MEMBER_NAME`      varchar(50)  COMMENT '成员备注名(max50)，如"爸爸/保洁阿姨"',
  `DAY_LIMIT_ML`     bigint       COMMENT '单日限额(毫升)，空=不限',
  `EFFECTIVE_TIME`   varchar(14)  COMMENT '授权生效时间，空=立即生效',
  `EXPIRE_TIME`      varchar(14)  COMMENT '授权失效时间，空=长期有效',
  `MEMBER_STATUS`    tinyint      NOT NULL COMMENT '授权状态(1333)：1生效 2已解除',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：同一张卡对同一用户至多一条授权记录（不含 DATA_STATUS，
  -- 逻辑删除记录仍占键），重新授权复用原记录；成员取水事务按该键行锁串行化日限额校验。
  UNIQUE INDEX `uk_card_member_user` (`CARD_ID`, `MEMBER_USER_ID`),
  INDEX `idx_member_card` (`CARD_ID`),
  INDEX `idx_member_user` (`MEMBER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='水卡成员授权表';

-- ----------------------------
-- 字典
-- ----------------------------
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('套餐状态', '1330', '水卡套餐上下架');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1330', 1, 1, '在售');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1330', 2, 2, '下架');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('卡类型', '1331', '水卡类型');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1331', 1, 1, '虚拟卡');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1331', 2, 2, '实体卡');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('卡状态', '1332', '水卡生命周期状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1332', 1, 1, '正常');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1332', 2, 2, '冻结');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1332', 3, 3, '已过期');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1332', 4, 4, '已注销');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('成员授权状态', '1333', '一卡多人授权状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1333', 1, 1, '生效');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1333', 2, 2, '已解除');

-- ----------------------------
-- 测试数据（两套餐两卡一授权；USER_ID 依赖 ws_user 测试用户，联调前按实际ID调整）
-- ----------------------------
INSERT IGNORE INTO `ws_package` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `PACKAGE_NAME`, `PAY_AMOUNT`, `WATER_ML`, `BONUS_AMOUNT`, `UNIT_PRICE_SNAP`, `SCOPE_JSON`, `EXPIRE_DAYS`, `PACKAGE_STATUS`, `PACKAGE_REMARK`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', '100元500升卡', 10000, 500000, 0, '20.00', '{"scopeType":"specified","stationIds":["1"]}', NULL, 1, '一期主推：1升=0.2元；付费卡永久有效（D-213）'),
(2, 0, 1, '20260710120000', 1, '20260710120000', '50元充值(送5元)', 5000, 0, 500, '0', NULL, NULL, 1, '纯余额充值，按出水口单价计费');

INSERT IGNORE INTO `ws_card` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `CARD_NO`, `CARD_TYPE`, `USER_ID`, `BALANCE_AMOUNT`, `BALANCE_ML`, `PACKAGE_ID`, `PACKAGE_SNAP`, `SCOPE_JSON`, `EXPIRE_TIME`, `CARD_STATUS`, `CARD_REMARK`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', 'VC20260710000001', 1, 1, 5500, 495000, 1, '{"packageName":"100元500升卡","payAmount":10000,"waterMl":500000,"unitPriceSnap":"20.00"}', '{"scopeType":"specified","stationIds":[1],"stationNames":["光谷软件园水站"]}', NULL, 1, '光谷社区主力水卡（付费卡永久有效 D-213；范围=发卡套餐快照的站级范围，与套餐1一致，同款续充才能过精确相等校验）'),
(2, 0, 1, '20260710120000', 1, '20260710120000', 'PC-8800001', 2, 1, 0, 100000, NULL, NULL, NULL, NULL, 2, '实体卡样例（冻结态，验证拦截）');

INSERT IGNORE INTO `ws_card_member` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `CARD_ID`, `MEMBER_USER_ID`, `MEMBER_NAME`, `DAY_LIMIT_ML`, `MEMBER_STATUS`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', 1, 4, '赵先生', 20000, 1);

-- ----------------------------
-- 菜单与权限统一由 03-demo-baseline.sql 建立
-- ----------------------------



-- ============================================================
-- 六维达康 · 交易域（ws_trade）
-- 表：ws_order / ws_payment / ws_refund / ws_wallet_flow / ws_split_record
-- 字典段：1340 订单类型、1341 订单状态、1342 支付状态、1343 退款状态、
--        1344 流水类型、1345 分账状态、1346 支付方式
-- 需求映射：订单管理 / 财务流水与对账 / 微信小程序JSAPI支付 / 支付回调验签与幂等 /
--          退款和退款回调 / 分账能力 / 售水分账 / 配送分账 / 出水不足超量异常（补偿）
-- 资金铁律：
--   1) 金额存"分"、水量存"毫升"（bigint）
--   2) 余额/水量扣减必须原子 UPDATE ... WHERE 余量>=n，同事务写 ws_wallet_flow
--   3) 微信回调幂等靠 uk_transaction_id + 订单状态机单向迁移双保险
--   4) 流水表只插入不更新（对账以流水为准）
-- ============================================================

-- ----------------------------
-- 订单表（取水/充值购卡/配送三类共用主表，类型字段区分）
-- ----------------------------
DROP TABLE IF EXISTS `ws_order`;
CREATE TABLE `ws_order` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `ORDER_NO`         varchar(32)  NOT NULL COMMENT '订单号(max32)，系统生成，微信 out_trade_no',
  `ORDER_TYPE`       tinyint      NOT NULL COMMENT '订单类型(1340)：1扫码取水 2购卡充值 3水配送',
  `USER_ID`          bigint       NOT NULL COMMENT '下单用户ID（ws_user.ID）',
  `STATION_ID`       bigint       COMMENT '水站ID（取水/配送单必填）',
  `DEVICE_ID`        bigint       COMMENT '设备ID（取水单必填）',
  `OUTLET_ID`        bigint       COMMENT '出水口ID（取水单必填）',
  `CARD_ID`          bigint       COMMENT '支付用水卡ID（卡支付时必填）',
  `PACKAGE_ID`       bigint       COMMENT '套餐ID（购卡充值单必填）',
  `PACKAGE_SNAP`     text         COMMENT '套餐/价格快照JSON（下单时价格、单价，退款折算依据）',
  `PLAN_ML`          bigint       COMMENT '计划水量(毫升)（取水单）',
  `ACTUAL_ML`        bigint       COMMENT '实际水量(毫升)（设备回传后回填，异常补偿依据）',
  `ORDER_AMOUNT`     bigint       NOT NULL COMMENT '订单金额(分)',
  `PAY_WAY`          tinyint      NOT NULL COMMENT '支付方式(1346)：1微信支付 2水卡余额 3水卡水量',
  `ORDER_STATUS`     tinyint      NOT NULL COMMENT '订单状态(1341)：1待支付 2已支付 3出水中 4已完成 5已取消 6异常待补偿 7已退款 8部分退款',
  `CMD_ID`           bigint       COMMENT '关联出水指令ID（ws_command.ID）',
  `FINISH_TIME`      varchar(14)  COMMENT '完成时间',
  `CANCEL_REASON`    varchar(500) COMMENT '取消/异常原因(max500)',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：订单号是微信支付 out_trade_no 与资金对账主键，必须库层唯一。
  UNIQUE INDEX `uk_order_no` (`ORDER_NO`),
  INDEX `idx_order_user` (`USER_ID`),
  INDEX `idx_order_device_time` (`DEVICE_ID`, `CREATE_TIME`),
  -- E2E-06 机主经营聚合站轨：按 (水站, 时间窗) 聚合订单；缺它则站维度查询全表扫描
  INDEX `idx_order_station_time` (`STATION_ID`, `CREATE_TIME`),
  INDEX `idx_order_status` (`ORDER_STATUS`),
  -- CARD-MEMBER：成员日限额统计（同卡同成员同类型按当天时间窗聚合取水订单）
  INDEX `idx_order_card_user_type_time` (`CARD_ID`, `USER_ID`, `ORDER_TYPE`, `CREATE_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单表';

-- ----------------------------
-- 支付单表（微信支付一单一记录；回调幂等主键 TRANSACTION_ID）
-- ----------------------------
DROP TABLE IF EXISTS `ws_payment`;
CREATE TABLE `ws_payment` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `ORDER_ID`         bigint       NOT NULL COMMENT '订单ID',
  `ORDER_NO`         varchar(32)  NOT NULL COMMENT '商户订单号(out_trade_no)',
  `TRANSACTION_ID`   varchar(64)  COMMENT '微信支付单号(max64)，回调后回填，幂等去重键',
  `PAY_AMOUNT`       bigint       NOT NULL COMMENT '支付金额(分)',
  `PAY_STATUS`       tinyint      NOT NULL COMMENT '支付状态(1342)：1待支付 2支付成功 3支付失败 4已关闭',
  -- 无 DEFAULT 是刻意的：PAY_SOURCE 是支付单权威来源，只能由服务端适配器写入。
  -- 若给合法值作默认（如 1=微信），适配器漏写会从「INSERT 报错回滚」降级为
  -- 「静默生成一张自称微信收款的支付单」——把 Pay-Sim 单记成真实收款，是对账上最坏的方向。
  -- 历史行回填由迁移脚本的 ALTER ... DEFAULT 负责，权威结构本身不留默认。
  `PAY_SOURCE`       tinyint      NOT NULL COMMENT '支付来源：1微信 2Pay-Sim；由服务端支付适配器创建时写入，创建后不可改（L2-DB 契约§5.2）',
  `CURRENCY`         varchar(16)  NOT NULL DEFAULT 'CNY' COMMENT '币种，一期固定CNY（L2-DB 契约§5.2）；二期支持多币种前必须移除该默认',
  `PAY_EXPIRE_TIME`  varchar(14)  NOT NULL COMMENT '创单时冻结的支付截止时间(契约§2.2算法)，创建后不可修改（L2-DB 契约§5.2）',
  `PAY_SUCCESS_TIME` varchar(14)  NULL DEFAULT NULL COMMENT '权威支付成功时间(Asia/Shanghai)（L2-DB 契约§5.2）',
  `PREPAY_ID`        varchar(64)  COMMENT '预支付ID(max64)',
  `CALLBACK_TIME`    varchar(14)  COMMENT '回调时间',
  `CALLBACK_PAYLOAD` text         COMMENT '回调原文JSON（验签后存档，对账依据）',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：微信回调可能重复推送，TRANSACTION_ID 唯一索引是
  -- "重复回调不重复入账"的数据库层最后防线（NULL 不参与唯一约束，未回调前可多行为空）。
  UNIQUE INDEX `uk_transaction_id` (`TRANSACTION_ID`),
  -- L2-DB 契约§5.1：一单一支付单，数据库层挡住"一单多支付单"（均不含 DATA_STATUS）。
  UNIQUE KEY `uk_payment_order_no` (`ORDER_NO`),
  UNIQUE KEY `uk_payment_order_id` (`ORDER_ID`),
  INDEX `idx_payment_order` (`ORDER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='支付单表';

-- ----------------------------
-- 支付回调事实收件箱表（L2-DB 契约§5.3）
-- 外部输入一律先落收件箱事实、再处理；不得"只信报文直接改账"。
-- ----------------------------
DROP TABLE IF EXISTS `ws_payment_event`;
CREATE TABLE `ws_payment_event` (
  `ID`                          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`                 tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：支付事实必须保持0，不做业务逻辑删除',
  `CREATE_BY`                   bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`                 varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`                   bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`                 varchar(14)  NOT NULL COMMENT '更新时间',
  `PAY_SOURCE`                  tinyint      NOT NULL COMMENT '支付来源：1微信 2Pay-Sim（服务端适配器常量，不取自报文）',
  `FACT_CHANNEL`                tinyint      NOT NULL COMMENT '事实渠道：1通知 2查询 3Pay-Sim',
  `PROVIDER_EVENT_KEY`          varchar(100) NOT NULL COMMENT '外部事实键：通知id / Q:<sha256规范串> / Pay-Sim规范UUID，三者命名空间不得复用',
  `PAYMENT_ID`                  bigint       NULL COMMENT '可信共键关联后的支付单ID，未知/错位时为空',
  `ORDER_ID`                    bigint       NULL COMMENT '可信共键关联后的订单ID，未知/错位时为空',
  `ORDER_NO`                    varchar(32)  NOT NULL COMMENT '已验证/解密的外部商户订单号',
  `TRADE_STATE`                 varchar(32)  NOT NULL COMMENT '规范化支付事实状态：SUCCESS/NOTPAY/CLOSED，其他仅留证转人工',
  `TRANSACTION_ID`              varchar(64)  NULL COMMENT '支付方交易号；SUCCESS必填，Pay-Sim用不重叠命名空间',
  `PAY_AMOUNT`                  bigint       NULL COMMENT '支付方返回金额(分)；SUCCESS必填，未返回必须为空，禁止用内部金额补造',
  `CURRENCY`                    varchar(16)  NULL COMMENT '支付方返回币种；SUCCESS必填且为CNY，未返回必须为空',
  `PAY_SUCCESS_TIME`            varchar(14)  NULL COMMENT '支付成功时间(Asia/Shanghai)；SUCCESS必填',
  `RAW_BODY`                    mediumtext   NULL COMMENT '原始签名正文/受保护查询证据（受M5保护）',
  `RAW_BODY_SHA256`             char(64)     NOT NULL COMMENT '正文完整性摘要（非不可抵赖证明）',
  `VERIFY_METHOD`               tinyint      NOT NULL COMMENT '校验方式：微信签名/微信查询/Pay-Sim HMAC',
  `SIGNATURE_SERIAL`            varchar(128) NULL COMMENT '通知重新核验材料：证书序列号',
  `SIGNATURE_TIMESTAMP`         varchar(32)  NULL COMMENT '通知重新核验材料：时间戳',
  `SIGNATURE_NONCE`             varchar(64)  NULL COMMENT '通知重新核验材料：随机串',
  `SIGNATURE_VALUE`             varchar(512) NULL COMMENT '通知重新核验材料：签名值',
  `PROCESSING_STATUS`           tinyint      NOT NULL COMMENT '处理状态：待处理/处理中/已处理/待重试/需对账',
  `RETRY_COUNT`                 int          NOT NULL DEFAULT 0 COMMENT '重试次数',
  `NEXT_RETRY_TIME`             varchar(14)  NULL COMMENT '下次可claim时间',
  `CLAIM_TIME`                  varchar(14)  NULL COMMENT 'Worker claim 时间',
  `LEASE_UNTIL`                 varchar(14)  NULL COMMENT 'claim 租约到期时间（崩溃恢复）',
  `RECOVERY_APPROVAL_GROUP_KEY` varchar(64)  NULL COMMENT '订单6同一支付事实组的恢复授权键，同组必须一致',
  `RECOVERY_APPROVED_BY`        bigint       NULL COMMENT '人工恢复授权人；仅受权对账动作可写',
  `RECOVERY_APPROVED_TIME`      varchar(14)  NULL COMMENT '人工恢复授权时间',
  `RECOVERY_APPROVAL_REASON`    varchar(500) NULL COMMENT '人工恢复依据，不得含敏感原文',
  `LAST_ERROR`                  varchar(500) NULL COMMENT '最近一次结构化失败原因，不写密钥/敏感正文',
  `RECEIVED_TIME`               varchar(14)  NOT NULL COMMENT '服务端接收时间',
  `PROCESSED_TIME`              varchar(14)  NULL COMMENT '该事实完成业务处理的时间',
  `RAW_PURGED_TIME`             varchar(14)  NULL COMMENT '原始证据清理时间',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_payment_event_source_channel_key` (`PAY_SOURCE`, `FACT_CHANNEL`, `PROVIDER_EVENT_KEY`),
  INDEX `idx_payment_event_order_no` (`ORDER_NO`),
  INDEX `idx_payment_event_claim` (`PROCESSING_STATUS`, `NEXT_RETRY_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='支付回调事实收件箱表';

-- ----------------------------
-- 退款单表
-- ----------------------------
DROP TABLE IF EXISTS `ws_refund`;
CREATE TABLE `ws_refund` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `REFUND_NO`        varchar(32)  NOT NULL COMMENT '退款单号(out_refund_no)',
  `ORDER_ID`         bigint       NOT NULL COMMENT '订单ID',
  `ORDER_NO`         varchar(32)  NOT NULL DEFAULT '' COMMENT '商户订单号，与退款事实的 ORDER_NO 对齐用',
  `PAYMENT_ID`       bigint       NOT NULL COMMENT '支付单ID',
  `AFTER_SALE_ID`    bigint       NULL COMMENT '关联售后动作ID(ws_after_sale_action.ID)；一个售后动作至多一张退款单',
  `REFUND_AMOUNT`    bigint       NOT NULL COMMENT '退款金额(分)，套餐快照单价折算',
  `REFUND_SOURCE`    tinyint      NOT NULL COMMENT '退款来源(1373)：1微信 2Refund-Sim；服务端适配器常量，不取自报文',
  `CURRENCY`         varchar(16)  NOT NULL DEFAULT 'CNY' COMMENT '币种；退款事实回填时必须与本列一致',
  `REFUND_REASON`    varchar(500) NOT NULL COMMENT '退款原因(max500)：出水不足补偿/用户取消/申诉赔付',
  `REFUND_STATUS`    tinyint      NOT NULL COMMENT '退款状态(1343)：1退款中 2退款成功 3退款失败 4待重试 5需人工对账',
  `PROVIDER_REFUND_ID` varchar(64) NULL COMMENT '支付机构退款单号(max64)；微信为refund_id，Refund-Sim为模拟号',
  `REQUEST_TIME`     varchar(14)  NULL COMMENT '退款请求发出时间',
  `SUCCESS_TIME`     varchar(14)  NULL COMMENT '支付机构确认退款成功的时间（取自事实，不取本地时钟）',
  `VERSION`          int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，退款状态机CAS的前态条件之一',
  `RETRY_COUNT`      int          NOT NULL DEFAULT 0 COMMENT '重试次数',
  `NEXT_RETRY_TIME`  varchar(14)  NULL COMMENT '下次可重试时间',
  `LAST_ERROR`       varchar(500) NULL COMMENT '最近一次失败原因；不写密钥、报文原文与本机路径',
  `CALLBACK_TIME`    varchar(14)  COMMENT '退款回调时间',
  `CALLBACK_PAYLOAD` text         COMMENT '退款回调原文JSON',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：退款单号是资金安全键，重复发起将造成重复退款。
  UNIQUE INDEX `uk_refund_no` (`REFUND_NO`),
  -- 一个售后动作至多一张退款单：重复退款的物理闸，不靠应用层查重（铁律②）。
  -- NULL 不参与唯一性，非售后来源的退款单不受影响。
  UNIQUE INDEX `uk_refund_after_sale` (`AFTER_SALE_ID`),
  INDEX `idx_refund_order` (`ORDER_ID`),
  INDEX `idx_refund_claim` (`REFUND_STATUS`, `NEXT_RETRY_TIME`),
  -- 支付单维度的累计封顶要按 PAYMENT_ID 聚合已成功退款额，缺它会退化成全表扫
  INDEX `idx_refund_payment` (`PAYMENT_ID`, `REFUND_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='退款单表（E2E-04 包B 起表示支付机构退款，不表示水卡余额/水量返还）';

-- ----------------------------
-- 退款事实收件箱（E2E-04 包B）
--
-- 与 deploy/mysql/migrations/2026-07-29-aftersale-e2e04-b.sql 是同一份 schema 的两个投放口：
-- 迁移供已有库升级，本段供全新环境首次初始化。两处必须逐列一致，改一处要改两处。
-- 形状对齐 ws_payment_event：退款事实与支付事实面对同一类问题（重复、乱序、迟到、
-- 错金额、处理者崩溃），两套形状会让 Worker 与恢复逻辑各写一遍，写错任何一份都是资金事故。
-- ----------------------------
DROP TABLE IF EXISTS `ws_refund_event`;
CREATE TABLE `ws_refund_event` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：退款事实必须保持0，不做业务逻辑删除',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间',
  `REFUND_SOURCE`       tinyint      NOT NULL COMMENT '退款来源(1373)：1微信 2Refund-Sim（服务端适配器常量，不取自报文）',
  `FACT_CHANNEL`        tinyint      NOT NULL COMMENT '事实渠道：1通知 2查询 3Refund-Sim',
  `PROVIDER_EVENT_KEY`  varchar(100) NOT NULL COMMENT '外部事实键：通知id / Q:<sha256规范化查询>；与来源+渠道构成幂等键',
  `REFUND_ID`           bigint       NULL COMMENT '可信共键关联后的退款单ID，未知/错位时为空',
  `AFTER_SALE_ID`       bigint       NULL COMMENT '可信共键关联后的售后动作ID，未知/错位时为空',
  `ORDER_ID`            bigint       NULL COMMENT '可信共键关联后的订单ID，未知/错位时为空',
  `REFUND_NO`           varchar(32)  NOT NULL COMMENT '已验证的商户退款单号(out_refund_no)',
  `ORDER_NO`            varchar(32)  NULL COMMENT '事实携带的商户订单号，用于交叉核对',
  `REFUND_STATE`        varchar(32)  NOT NULL COMMENT '规范化退款事实状态：SUCCESS/PROCESSING/CLOSED/ABNORMAL/UNKNOWN',
  `PROVIDER_REFUND_ID`  varchar(64)  NULL COMMENT '支付机构退款单号；SUCCESS必填',
  `REFUND_AMOUNT`       bigint       NULL COMMENT '支付机构返回退款金额(分)；SUCCESS必填，未返回必须为空，禁止用内部值顶替',
  `CURRENCY`            varchar(16)  NULL COMMENT '支付机构返回币种；SUCCESS必填且为CNY',
  `REFUND_SUCCESS_TIME` varchar(14)  NULL COMMENT '退款成功时间(Asia/Shanghai)；SUCCESS必填',
  `RAW_BODY`            mediumtext   NULL COMMENT '原始签名正文/受保护查询证据；禁止出接口',
  `RAW_BODY_SHA256`     char(64)     NOT NULL COMMENT '正文完整性摘要：同键重复到达时比对，不一致即转人工',
  `VERIFY_METHOD`       tinyint      NOT NULL COMMENT '校验方式：1微信签名 2微信查询 3Refund-Sim HMAC',
  `PROCESSING_STATUS`   tinyint      NOT NULL COMMENT '处理状态：1待处理 2处理中 3已处理 4待重试 5需对账',
  `RETRY_COUNT`         int          NOT NULL DEFAULT 0 COMMENT '重试次数',
  `NEXT_RETRY_TIME`     varchar(14)  NULL COMMENT '下次可claim时间',
  `CLAIM_TIME`          varchar(14)  NULL COMMENT 'Worker claim 时间',
  `LEASE_UNTIL`         varchar(14)  NULL COMMENT 'claim 租约到期时间（处理者崩溃后据此恢复）',
  `LAST_ERROR`          varchar(500) NULL COMMENT '最近一次结构化失败原因；不写密钥与报文原文',
  `RECEIVED_TIME`       varchar(14)  NOT NULL COMMENT '服务端接收时间',
  `PROCESSED_TIME`      varchar(14)  NULL COMMENT '该事实完成业务处理的时间',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_refund_event_source_channel_key` (`REFUND_SOURCE`, `FACT_CHANNEL`, `PROVIDER_EVENT_KEY`),
  INDEX `idx_refund_event_refund_no` (`REFUND_NO`),
  INDEX `idx_refund_event_claim` (`PROCESSING_STATUS`, `NEXT_RETRY_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='退款事实收件箱表（E2E-04 包B）';

-- ----------------------------
-- 钱包流水表（水卡余额/水量每次增减一条；只插入不更新；对账以此为准）
-- ----------------------------
DROP TABLE IF EXISTS `ws_wallet_flow`;
CREATE TABLE `ws_wallet_flow` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `CARD_ID`          bigint       NOT NULL COMMENT '水卡ID',
  `USER_ID`          bigint       NOT NULL COMMENT '操作用户ID（成员用卡时为成员ID，审计归属）',
  `FLOW_TYPE`        tinyint      NOT NULL COMMENT '流水类型(1344)：1充值入账 2取水扣减 3退款返还 4补偿入账 5后台调整 6过期清零 7配送扣减',
  `AMOUNT_CHANGE`    bigint       NOT NULL DEFAULT 0 COMMENT '余额变动(分)，正入负出',
  `ML_CHANGE`        bigint       NOT NULL DEFAULT 0 COMMENT '水量变动(毫升)，正入负出',
  `AMOUNT_AFTER`     bigint       NOT NULL COMMENT '变动后余额(分)快照',
  `ML_AFTER`         bigint       NOT NULL COMMENT '变动后水量(毫升)快照',
  `ORDER_ID`         bigint       COMMENT '关联订单ID',
  `FLOW_REMARK`      varchar(500) COMMENT '备注(max500)',
  `BIZ_IDEMPOTENCY_KEY` varchar(64) NULL DEFAULT NULL COMMENT '业务幂等键，充值入账固定 RECHARGE:<orderNo>、配送扣减固定 DELIVERY:<orderNo>；不含DATA_STATUS，错误标记删除也不得复用（L2-DB 契约§5.1）',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_wallet_flow_biz_key` (`BIZ_IDEMPOTENCY_KEY`),
  INDEX `idx_flow_card_time` (`CARD_ID`, `CREATE_TIME`),
  INDEX `idx_flow_order` (`ORDER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='钱包流水表';

-- ----------------------------
-- 分账记录表（订单完成后按规则生成；机主/配送/平台各一条）
-- ----------------------------
DROP TABLE IF EXISTS `ws_split_record`;
CREATE TABLE `ws_split_record` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `ORDER_ID`         bigint       NOT NULL COMMENT '订单ID',
  `RECEIVER_TYPE`    tinyint      NOT NULL COMMENT '接收方类型(1377)：1机主 2配送员 3平台 4渠道(预留) 5推荐人(预留) 6区域服务商(预留)',
  `RECEIVER_USER_ID` bigint       COMMENT '接收方用户ID；平台行恒 0 哨兵（NULL 不参与唯一约束会破幂等，禁止写 NULL）',
  `SPLIT_AMOUNT`     bigint       NOT NULL COMMENT '分账金额(分)',
  `SPLIT_RATE_SNAP`  varchar(20)  NOT NULL COMMENT '分账比例快照：整数万分比(如"7000")；D-419 配送分线后机主="W<水费线>+D<配送费线>"、配送员="D<配送费线>"；平台余数行="REMAINDER"；规则变更不影响历史',
  `SPLIT_STATUS`     tinyint      NOT NULL COMMENT '分账状态(1345)：1待分账 2已分账 3分账失败 4已回退',
  `WX_SPLIT_NO`      varchar(64)  COMMENT '微信分账单号(max64)',
  `SPLIT_TIME`       varchar(14)  COMMENT '分账完成时间',
  `SPLIT_REMARK`     varchar(500) COMMENT '备注(max500)',
  `REFUND_ID`        bigint       COMMENT '历史兼容字段（R1 起不再承担幂等职责，冲减幂等归 ws_split_clawback 唯一键；保留读兼容）',
  `REVERSED_AMOUNT`  bigint       NOT NULL DEFAULT 0 COMMENT '累计已冲减金额(分)（D-420 R1）：多次部分退款逐笔累加（明细在 ws_split_clawback）；结算与入账按净额=SPLIT_AMOUNT-本列；恒不超过行水费线原始份额。0=未冲减',
  PRIMARY KEY (`ID`),
  -- 同单同收款方恒一行：铁律②库层幂等，分账 Worker 并发/重放零重复（E2E-08 包A）
  UNIQUE KEY `uk_split_order_receiver` (`ORDER_ID`, `RECEIVER_TYPE`, `RECEIVER_USER_ID`),
  INDEX `idx_split_order` (`ORDER_ID`),
  INDEX `idx_split_receiver` (`RECEIVER_TYPE`, `RECEIVER_USER_ID`),
  -- D-421 R1-P2：钱包在途分润聚合（RECEIVER_USER_ID+SPLIT_STATUS 过滤、CREATE_TIME 取 MIN）
  -- 走索引——复验 EXPLAIN 实测旧查询 type=ALL 全表扫描，随全平台分账量退化
  INDEX `idx_split_pending_wallet` (`RECEIVER_USER_ID`, `SPLIT_STATUS`, `CREATE_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='分账记录表';

-- ============================================================
-- E2E-08 分账比例配置 / 收益钱包（与 migrations/2026-07-31-settlement-e2e08-a.sql 双份逐字一致）
-- ============================================================
CREATE TABLE `ws_split_config` (
  `ID`            bigint      NOT NULL AUTO_INCREMENT,
  `DATA_STATUS`   tinyint     NOT NULL DEFAULT 0,
  `CREATE_BY`     bigint      NOT NULL,
  `CREATE_TIME`   varchar(14) NOT NULL,
  `UPDATE_BY`     bigint      NOT NULL,
  `UPDATE_TIME`   varchar(14) NOT NULL,
  `PRODUCT_LINE`  tinyint     NOT NULL COMMENT '商品线(1376)：1售水 2配送',
  `RECEIVER_TYPE` tinyint     NOT NULL COMMENT '收款方类型(1377)：1机主 2配送员 3平台 4渠道(预留) 5推荐人(预留) 6区域服务商(预留)',
  `SPLIT_RATE`    int         NOT NULL COMMENT '比例（万分比，如 7000=70%；整除余数恒归平台）',
  `EFFECT_TIME`   varchar(14) NOT NULL COMMENT '生效时间（含）；取订单创建时点生效版本，变更不追溯',
  `CONFIG_REMARK` varchar(200) NULL,
  PRIMARY KEY (`ID`),
  -- 同线同收款方同生效时点恒一行：改比例=插新生效版本，历史版本只读（快照可审计）
  UNIQUE KEY `uk_split_config_version` (`PRODUCT_LINE`, `RECEIVER_TYPE`, `EFFECT_TIME`),
  INDEX `idx_split_config_effect` (`PRODUCT_LINE`, `EFFECT_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='分账比例配置表（版本化，演示值待甲方确认后调整）';

-- 分账比例配置的商品线锁锚表（R1 P1-3）：每线恒一行，仅作 configCreate 写入事务的
-- 行锁锚（锁行→读全部版本→校验全部未来断点→INSERT），同线串行化、异线并发；无业务数据。
CREATE TABLE `ws_split_line_lock` (
  `PRODUCT_LINE` tinyint NOT NULL COMMENT '商品线(1376)：1售水 2配送——每线恒一行，仅作配置写入的行锁锚',
  PRIMARY KEY (`PRODUCT_LINE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='分账比例配置的商品线锁锚表（配置写入串行化，无业务数据）';

INSERT INTO `ws_split_line_lock` (`PRODUCT_LINE`) VALUES (1), (2);


-- ============================================================
-- 分润 V2 S1：完整计划模型与组件证据（与 migrations/2026-08-06-split-v2-s1.sql 双份逐字一致）
-- 正式比例待甲方确认：不插任何计划种子；V2 开关两环境默认 false。
-- ============================================================
CREATE TABLE `ws_split_plan` (
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

CREATE TABLE `ws_split_plan_item` (
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

CREATE TABLE `ws_split_component` (
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

CREATE TABLE `ws_income_account` (
  `ID`             bigint      NOT NULL AUTO_INCREMENT,
  `DATA_STATUS`    tinyint     NOT NULL DEFAULT 0,
  `CREATE_BY`      bigint      NOT NULL,
  `CREATE_TIME`    varchar(14) NOT NULL,
  `UPDATE_BY`      bigint      NOT NULL,
  `UPDATE_TIME`    varchar(14) NOT NULL,
  `USER_ID`        bigint      NOT NULL COMMENT '收益人（ws_user.ID）；查询按会话强制过滤（铁律6）',
  `BALANCE_FEN`    bigint      NOT NULL DEFAULT 0 COMMENT '可用分润余额(分)；恒等于末笔流水 AFTER（对账不变式）',
  `FROZEN_FEN`     bigint      NOT NULL DEFAULT 0 COMMENT '提现审核冻结中(分)',
  `CLAWBACK_DEFICIT_FEN` bigint NOT NULL DEFAULT 0 COMMENT '冲减待补差额(分)（D-420/C-04.1 选项B）：退款扣回时可用余额不足的缺口，恒>=0。>0 时禁止提现（fail-closed）；后续分润入账先补此差额再进可用余额，补足即解除限制；不记负余额、公司不兜底',
  `VERSION`        int         NOT NULL DEFAULT 1 COMMENT '乐观锁：余额变动走前值+VERSION 双条件 CAS',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_income_account_user` (`USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收益账户（分润余额，与水卡余额/用户余额物理隔离，REQ-072）';

CREATE TABLE `ws_income_flow` (
  `ID`             bigint      NOT NULL AUTO_INCREMENT,
  `DATA_STATUS`    tinyint     NOT NULL DEFAULT 0,
  `CREATE_BY`      bigint      NOT NULL,
  `CREATE_TIME`    varchar(14) NOT NULL,
  `UPDATE_BY`      bigint      NOT NULL,
  `UPDATE_TIME`    varchar(14) NOT NULL,
  `USER_ID`        bigint      NOT NULL COMMENT '收益人；按会话强制过滤（铁律6）',
  `FLOW_TYPE`      tinyint     NOT NULL COMMENT '收益流水类型(1378)：1分润入账 2分润回退(预留) 3提现冻结 4提现完成(预留) 5提现驳回解冻',
  `AMOUNT_FEN`     bigint      NOT NULL COMMENT '变动金额(分)，入账为正、出账为负',
  `AFTER_FEN`      bigint      NOT NULL COMMENT '变动后可用余额(分)；对账以逐笔连续为不变式',
  `SPLIT_ID`       bigint      NULL COMMENT '来源分账记录（1/2 类必填：余额变动可追溯到分账与原始订单，REQ-072）',
  `ORDER_NO`       varchar(32) NULL COMMENT '原始订单号快照（省联查，追溯口径与 SPLIT_ID 同源）',
  `BIZ_IDEMPOTENCY_KEY` varchar(64) NOT NULL COMMENT '幂等键：INCOME:<splitId> / WITHDRAW:<申请号>（前缀命名空间已登记，防截断碰撞）',
  `FLOW_REMARK`    varchar(200) NULL,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_income_flow_biz_key` (`BIZ_IDEMPOTENCY_KEY`),
  INDEX `idx_income_flow_user_time` (`USER_ID`, `CREATE_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收益流水（只插不更新；禁止与 ws_wallet_flow 混表，REQ-072 验收补充）';

CREATE TABLE `ws_reconcile_task` (
  `ID`            bigint      NOT NULL AUTO_INCREMENT,
  `DATA_STATUS`   tinyint     NOT NULL DEFAULT 0,
  `CREATE_BY`     bigint      NOT NULL,
  `CREATE_TIME`   varchar(14) NOT NULL,
  `UPDATE_BY`     bigint      NOT NULL,
  `UPDATE_TIME`   varchar(14) NOT NULL,
  `BIZ_DATE`      varchar(8)  NOT NULL COMMENT '账期日（yyyyMMdd，日切按流水/事实 CREATE_TIME 前缀）',
  `TASK_STATUS`   tinyint     NOT NULL COMMENT '对账任务状态(1380)：1执行中 2平账 3有差异',
  `CHECK_TOTAL`   int         NOT NULL DEFAULT 0 COMMENT '本批核对项数',
  `DIFF_TOTAL`    int         NOT NULL DEFAULT 0 COMMENT '差异项数',
  `TASK_REMARK`   varchar(500) NULL,
  PRIMARY KEY (`ID`),
  -- 同账期恒一行：重跑=先删旧差异再重算（差异台账以最新一轮为准），任务行原位更新
  UNIQUE KEY `uk_reconcile_task_date` (`BIZ_DATE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='日对账批任务';

CREATE TABLE `ws_reconcile_diff` (
  `ID`            bigint      NOT NULL AUTO_INCREMENT,
  `DATA_STATUS`   tinyint     NOT NULL DEFAULT 0,
  `CREATE_BY`     bigint      NOT NULL,
  `CREATE_TIME`   varchar(14) NOT NULL,
  `UPDATE_BY`     bigint      NOT NULL,
  `UPDATE_TIME`   varchar(14) NOT NULL,
  `TASK_ID`       bigint      NOT NULL COMMENT '所属对账批',
  `BIZ_DATE`      varchar(8)  NOT NULL,
  `DIFF_TYPE`     tinyint     NOT NULL COMMENT '差异分类(1379)：1单边账 2金额不符 3状态不符 4账本断裂',
  `CHECK_DIMENSION` varchar(30) NOT NULL COMMENT '核对维度：payment-fact/order-flow/card-ledger/split-sum/income-ledger',
  `BIZ_KEY`       varchar(64) NOT NULL COMMENT '差异主体业务键（订单号/卡ID/账户ID）',
  `EXPECTED_VAL`  varchar(200) NULL COMMENT '期望值',
  `ACTUAL_VAL`    varchar(200) NULL COMMENT '实际值',
  `DIFF_REMARK`   varchar(500) NULL,
  PRIMARY KEY (`ID`),
  INDEX `idx_reconcile_diff_task` (`TASK_ID`),
  INDEX `idx_reconcile_diff_key` (`BIZ_DATE`, `BIZ_KEY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='日对账差异台账（每轮重算整批替换）';

-- 注意：1375 值 4（运营赠卡）不在本 init 灌——e2e04-d 迁移的后置不变式断言此刻恰 9 项，
-- 值 4 只能由 2026-07-31-settlement-e2e08-a.sql（文件名序在其后）增补，两轨最终收敛。
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`,`DICT_TYPE`,`DICT_REMARK`) VALUES
('分账商品线','1376','分账比例配置的商品线维度'),
('分账收款方类型','1377','与 ws_split_record.RECEIVER_TYPE 同源'),
('收益流水类型','1378','机主收益钱包流水（独立于 1344 水卡钱包）'),
('对账差异分类','1379','日对账差错台账'),
('对账任务状态','1380','日对账批任务'),
('提现审核状态','1381','提现申请骨架（不做真实出金）');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`) VALUES
(NULL,1,'1376',1,1,'售水'),(NULL,1,'1376',2,2,'配送'),
(NULL,1,'1377',1,1,'机主'),(NULL,1,'1377',2,2,'配送员'),(NULL,1,'1377',3,3,'平台'),
(NULL,1,'1377',4,4,'渠道(预留)'),(NULL,1,'1377',5,5,'推荐人(预留)'),(NULL,1,'1377',6,6,'区域服务商(预留)'),
(NULL,1,'1378',1,1,'分润入账'),(NULL,1,'1378',2,2,'分润回退(预留)'),(NULL,1,'1378',3,3,'提现冻结'),
(NULL,1,'1378',4,4,'提现完成(预留)'),(NULL,1,'1378',5,5,'提现驳回解冻'),
(NULL,1,'1379',1,1,'单边账'),(NULL,1,'1379',2,2,'金额不符'),(NULL,1,'1379',3,3,'状态不符'),(NULL,1,'1379',4,4,'账本断裂'),
(NULL,1,'1380',1,1,'执行中'),(NULL,1,'1380',2,2,'平账'),(NULL,1,'1380',3,3,'有差异'),
(NULL,1,'1381',1,1,'待审核'),(NULL,1,'1381',2,2,'审核通过(预留)'),(NULL,1,'1381',3,3,'已驳回');


-- ----------------------------
-- 字典
-- ----------------------------
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('订单类型', '1340', '订单业务类型');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1340', 1, 1, '扫码取水');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1340', 2, 2, '购卡充值');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1340', 3, 3, '水配送');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('订单状态', '1341', '订单状态机');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1341', 1, 1, '待支付');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1341', 2, 2, '已支付');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1341', 3, 3, '出水中');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1341', 4, 4, '已完成');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1341', 5, 5, '已取消');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1341', 6, 6, '异常待补偿');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1341', 7, 7, '已退款');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1341', 8, 8, '部分退款');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('支付状态', '1342', '微信支付单状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1342', 1, 1, '待支付');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1342', 2, 2, '支付成功');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1342', 3, 3, '支付失败');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1342', 4, 4, '已关闭');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('退款状态', '1343', '退款单状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1343', 1, 1, '退款中');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1343', 2, 2, '退款成功');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1343', 3, 3, '退款失败');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('流水类型', '1344', '钱包流水类型');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1344', 1, 1, '充值入账');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1344', 2, 2, '取水扣减');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1344', 3, 3, '退款返还');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1344', 4, 4, '补偿入账');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1344', 5, 5, '后台调整');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1344', 6, 6, '过期清零');
-- E2E-03 规则1/3：配送单用卡金额余额支付，扣减流水独立类型（幂等键 DELIVERY:<orderNo>）
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1344', 7, 7, '配送扣减');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('分账状态', '1345', '订单分账状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1345', 1, 1, '待分账');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1345', 2, 2, '已分账');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1345', 3, 3, '分账失败');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1345', 4, 4, '已回退');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('支付方式', '1346', '订单支付方式');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1346', 1, 1, '微信支付');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1346', 2, 2, '水卡余额');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1346', 3, 3, '水卡水量');

-- ----------------------------
-- 测试数据（一条完成的取水单+卡扣流水；一条待支付充值单；覆盖列表页开发）
-- ----------------------------
INSERT IGNORE INTO `ws_order` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `ORDER_NO`, `ORDER_TYPE`, `USER_ID`, `STATION_ID`, `DEVICE_ID`, `OUTLET_ID`, `CARD_ID`, `PACKAGE_ID`, `PACKAGE_SNAP`, `PLAN_ML`, `ACTUAL_ML`, `ORDER_AMOUNT`, `PAY_WAY`, `ORDER_STATUS`, `CMD_ID`, `FINISH_TIME`, `CANCEL_REASON`) VALUES
(1, 0, 1, '20260710130000', 1, '20260710130500', 'WO20260710130000001', 1, 1, 1, 1, 1, 1, NULL, '{"unitPriceSnap":"20.00"}', 5000, 5000, 0, 3, 4, 4, '20260710130500', NULL),
(2, 0, 1, '20260710140000', 1, '20260710140000', 'WO20260710140000002', 2, 1, NULL, NULL, NULL, NULL, 1, '{"packageName":"100元500升卡","payAmount":10000}', NULL, NULL, 10000, 1, 1, NULL, NULL, NULL),
(3, 0, 1, '20260710145500', 1, '20260710150000', 'WO20260710145500003', 3, 1, 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 3000, 1, 2, NULL, NULL, NULL);

INSERT IGNORE INTO `ws_wallet_flow` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `CARD_ID`, `USER_ID`, `FLOW_TYPE`, `AMOUNT_CHANGE`, `ML_CHANGE`, `AMOUNT_AFTER`, `ML_AFTER`, `ORDER_ID`, `FLOW_REMARK`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', 1, 1, 1, 5500, 500000, 5500, 500000, NULL, '开卡充值（含赠送500分）'),
(2, 0, 1, '20260710130500', 1, '20260710130500', 1, 1, 2, 0, -5000, 5500, 495000, 1, '扫码取水 5 升');

-- ----------------------------
-- 菜单与权限统一由 03-demo-baseline.sql 建立
-- ----------------------------







-- ============================================================
-- 六维达康 · 配送域（ws_delivery）
-- 表：ws_courier / ws_delivery_task / ws_delivery_appeal
--     ws_delivery_exception / ws_delivery_media / ws_delivery_auto_rule（E2E-03 包A）
-- 字典段：1350 配送员状态、1351 配送任务状态、1352 申诉状态、
--        1353 签收定位状态、1354 配送异常原因、1355 自动补货规则状态
-- 需求映射：配送员注册认证与准入管理 / 水配送接单与配送 / 拍照签收（三照）/
--          配送状态通知与签收申诉追踪 / 用户24小时申诉 / 一期水配送下单（订单在 ws_order type=3）
-- 说明：配送"订单"复用 ws_order（ORDER_TYPE=3），本域的 ws_delivery_task 是履约单，
--      一单一任务（uk_dtask_order 库层唯一），记录接单人、节点时间与三照签收。
--      水费/配送费在任务上分列快照，ws_order.ORDER_AMOUNT 恒等于两者之和（E2E-03 规则2）。
-- ============================================================

-- ----------------------------
-- 配送员表（准入：注册→后台审核→启用；关联水站/区域任务范围）
-- ----------------------------
DROP TABLE IF EXISTS `ws_courier`;
CREATE TABLE `ws_courier` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `USER_ID`          bigint       NOT NULL COMMENT '用户ID（ws_user.ID，配送员准入记录关联同一C端用户）',
  `COURIER_NAME`     varchar(50)  NOT NULL COMMENT '姓名(max50)',
  `COURIER_PHONE`    varchar(20)  NOT NULL COMMENT '联系电话(max20)',
  `ID_CARD_NO`       varchar(30)  COMMENT '身份证号(max30)，脱敏展示',
  `STATION_IDS`      varchar(200) COMMENT '服务水站ID集(逗号分隔,max200)，空=未配置并默认拒绝接单',
  `SERVICE_REGION`   varchar(100) COMMENT '服务区域(max100)',
  `COURIER_STATUS`   tinyint      NOT NULL COMMENT '状态(1350)：1待审核 2启用 3停用 4审核驳回',
  `AUDIT_REMARK`     varchar(500) COMMENT '审核备注(max500)',
  PRIMARY KEY (`ID`),
  INDEX `idx_courier_user` (`USER_ID`),
  INDEX `idx_courier_status` (`COURIER_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='配送员表';

-- ----------------------------
-- 配送任务表（履约单：ws_order(type=3) 支付后生成，一单一任务）
-- ----------------------------
DROP TABLE IF EXISTS `ws_delivery_task`;
CREATE TABLE `ws_delivery_task` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `TASK_NO`          varchar(32)  NOT NULL COMMENT '任务号(max32)：DT+sha256(orderNo)前30位大写十六进制，由订单号确定性派生（禁日期禁序列，重放恒定）',
  `ORDER_ID`         bigint       NOT NULL COMMENT '配送订单ID（ws_order，ORDER_TYPE=3）',
  `USER_ID`          bigint       NOT NULL COMMENT '收货用户ID（ws_user.ID）；接单/可接列表据此排除自配送（铁律7）',
  `STATION_ID`       bigint       NOT NULL COMMENT '配送水站ID快照（ws_station.ID）；配送员服务范围判定只吃本列，不回查订单（E2E-03 规则8）',
  `COURIER_ID`       bigint       COMMENT '接单配送员ID（ws_courier.ID，待接单时为空）；接单 CAS 以 IS NULL 为前置（E2E-03 规则9）',
  `WATER_TYPE_ID`    bigint       NOT NULL COMMENT '水种ID（ws_water_type.ID）；与水种名称快照共同构成履约共键',
  `WATER_TYPE`       varchar(20)  NOT NULL COMMENT '水种(max20)',
  `CONTAINER_SPEC`   varchar(20)  COMMENT '容器规格(max20)：3L袋/5L桶/10L桶/20L桶（后续预留）',
  `DELIVERY_COUNT`   int          NOT NULL COMMENT '计划配送数量(桶/袋)',
  `PLAN_RETURN_COUNT` int         NOT NULL COMMENT '计划回收空桶数量',
  `ACTUAL_DELIVERY_COUNT` int     COMMENT '实际配送数量（签收时结构化落库，签收前为空）',
  `ACTUAL_RETURN_COUNT`   int     COMMENT '实际回收数量（签收时结构化落库，签收前为空）',
  `WATER_AMOUNT`     bigint       NOT NULL COMMENT '水费快照(分)，下单时冻结',
  `DELIVERY_FEE`     bigint       NOT NULL COMMENT '配送费快照(分)，下单时冻结；ws_order.ORDER_AMOUNT=WATER_AMOUNT+DELIVERY_FEE（E2E-03 规则2）',
  `RECEIVE_ADDRESS`  varchar(200) NOT NULL COMMENT '收水地址(max200)',
  `RECEIVE_PHONE`    varchar(20)  NOT NULL COMMENT '收货电话(max20)，展示必须经 PhoneMask 脱敏',
  `TASK_STATUS`      tinyint      NOT NULL COMMENT '任务状态(1351)：1待接单 2已接单 3配送中 4已送达待确认 5已签收 6已取消 7申诉中',
  `VERSION`          int          NOT NULL COMMENT '乐观锁版本：创建=1，每次状态转换+1；所有转换必须 WHERE VERSION=期望值（E2E-03 规则11）',
  `SCHEDULED_TIME`   varchar(14)  COMMENT '预约配送时间；空=即时单付完即入池，非空=到点(<=now)才进入可接列表（E2E-03 规则19，查询时间语义，不建调度器）',
  `ACCEPT_TIME`      varchar(14)  COMMENT '接单时间',
  `DEPART_TIME`      varchar(14)  COMMENT '离站时间（水已离站节点）',
  `ARRIVE_TIME`      varchar(14)  COMMENT '送达时间',
  `SIGN_TIME`        varchar(14)  COMMENT '签收时间（服务端生成，非客户端上传；照片时间统一覆盖为本值）',
  `SIGN_PHOTOS`      text         COMMENT '签收三照JSON数组，元素{type:1门牌/2水品/3摆放, mediaKey, lat, lng, time}（受控媒体键引用 ws_delivery_media，时间与 SIGN_TIME 同源）',
  `LOCATION_STATUS`  tinyint      COMMENT '签收定位记录状态(1353)：1定位已记录 2定位未记录；签收前为空。声明1时三照必须携带合法坐标，禁止文案超出实际证据',
  `APPEAL_DEADLINE`  varchar(14)  COMMENT '申诉截止时间（签收+24h，签收事务内落定，申诉窗口判定唯一依据）',
  `TASK_REMARK`      varchar(500) COMMENT '备注(max500)',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范"不加唯一索引"：一单一有效任务是履约资金安全键（E2E-03 规则6），
  -- 并发重复建任务必须被数据库层挡住，不能依赖代码查重。
  UNIQUE INDEX `uk_dtask_order` (`ORDER_ID`),
  -- 任务号是小程序/PC 的履约追溯主键，库层唯一防派生冲突静默串单。
  UNIQUE INDEX `uk_dtask_task_no` (`TASK_NO`),
  INDEX `idx_dtask_courier_status` (`COURIER_ID`, `TASK_STATUS`),
  INDEX `idx_dtask_status` (`TASK_STATUS`),
  -- 可接任务池查询路径：按水站范围+状态过滤（配送员服务范围 ∩ 待接单）
  INDEX `idx_dtask_station_status` (`STATION_ID`, `TASK_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='配送任务表';

-- ----------------------------
-- 配送申诉表（签收后24h内可发起；后台处理：赔付走 ws_refund）
-- ----------------------------
DROP TABLE IF EXISTS `ws_delivery_appeal`;
CREATE TABLE `ws_delivery_appeal` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间（申诉动作时间，窗口判定 [SIGN_TIME, APPEAL_DEADLINE] 的落点）',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `TASK_ID`          bigint       NOT NULL COMMENT '配送任务ID（共键之一：必须与 ORDER_ID 指向同一任务-订单对）',
  `ORDER_ID`         bigint       NOT NULL COMMENT '配送订单ID（共键之一：必须等于任务的 ORDER_ID，错位 fail-closed）',
  `USER_ID`          bigint       NOT NULL COMMENT '申诉用户ID（限订单本人，Service 层强制，E2E-03 规则15）',
  `APPEAL_REASON`    varchar(20)  NOT NULL COMMENT '申诉原因码(max20)：QUANTITY数量不符/QUALITY质量问题/DAMAGE货损/PLACEMENT摆放不符/OTHER其他（对齐小程序契约枚举）',
  `APPEAL_DESC`      varchar(500) COMMENT '申诉说明(max500)；服务端创建时必填校验，历史行可空',
  `RECEIVED_COUNT`   int          COMMENT '用户实收数量（结构化数字）；服务端创建时必填校验，历史行可空',
  `APPEAL_PHOTOS`    text         COMMENT '申诉举证JSON数组（受控媒体键 mediaKey 引用 ws_delivery_media，非 URL）',
  `COURIER_EVIDENCES` text        COMMENT '配送员举证JSON数组，元素{description, evidenceRefs:[mediaKey], time}；只允许任务归属配送员在申诉中追加（E2E-03 规则16）',
  `APPEAL_STATUS`    tinyint      NOT NULL COMMENT '申诉状态(1352)：1待处理 2成立待补偿(资金补偿待处理，不写退款成功) 3不成立驳回 4撤销 5补送待执行（E2E-03 规则17/18）',
  `HANDLE_BY`        bigint       COMMENT '处理人（api_employee.ID）',
  `HANDLE_TIME`      varchar(14)  COMMENT '处理时间',
  `HANDLE_RESULT`    varchar(500) COMMENT '处理结果(max500)',
  -- 生成列：活动申诉(状态1)占位 TASK_ID，其余为 NULL；配合唯一键实现「一任务至多一条活动申诉」
  -- 的数据库层保证（NULL 不参与唯一约束，已裁决/撤销的历史申诉不占位）。没了它，
  -- 并发重复申诉只剩代码查重，可被双请求穿透。
  `ACTIVE_TASK_KEY`  bigint GENERATED ALWAYS AS (IF(`APPEAL_STATUS` = 1, `TASK_ID`, NULL)) STORED COMMENT '活动申诉唯一占位（生成列）',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_appeal_active_task` (`ACTIVE_TASK_KEY`),
  INDEX `idx_appeal_task` (`TASK_ID`),
  INDEX `idx_appeal_order` (`ORDER_ID`),
  INDEX `idx_appeal_status` (`APPEAL_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='配送申诉表';

-- ----------------------------
-- 配送异常记录表（配送员在 2已接单/3配送中/4已送达待确认 阶段上报；只插入不更新）
-- ----------------------------
DROP TABLE IF EXISTS `ws_delivery_exception`;
CREATE TABLE `ws_delivery_exception` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间（异常动作时间，不早于任务最后已发生节点）',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `TASK_ID`          bigint       NOT NULL COMMENT '配送任务ID',
  `COURIER_ID`       bigint       NOT NULL COMMENT '上报配送员ID（ws_courier.ID，必须等于任务归属配送员）',
  `EXCEPTION_REASON` tinyint      NOT NULL COMMENT '异常原因(1354)：1联系不上用户 2地址异常 3数量问题 4货物破损 5其他',
  `EXCEPTION_DESC`   varchar(500) NOT NULL COMMENT '异常说明(max500)',
  `EVIDENCE_REFS`    text         COMMENT '举证JSON数组（受控媒体键 mediaKey）',
  PRIMARY KEY (`ID`),
  INDEX `idx_dexc_task` (`TASK_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='配送异常记录表';

-- ----------------------------
-- 配送受控媒体表（E2E-03 包A：三照/举证以受控引用存库，不接对象存储；
-- 本地适配器按 MEDIA_KEY 定位文件，库行是媒体存在与归属的唯一权威）
-- ----------------------------
DROP TABLE IF EXISTS `ws_delivery_media`;
CREATE TABLE `ws_delivery_media` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `MEDIA_KEY`        varchar(64)  NOT NULL COMMENT '受控媒体键(max64)：DM+sha256(ownerUserId:contentSha256:purpose)前30位大写；引用方只存本键',
  `OWNER_USER_ID`    bigint       NOT NULL COMMENT '登记人在其门户内的ID（OWNER_PORTAL=2 时为 ws_user.ID，=1 时为 api_employee.ID）；引用校验必须同时匹配门户与ID，防跨身份空间挪用',
  `OWNER_PORTAL`     tinyint      NOT NULL DEFAULT 2 COMMENT '登记人门户(1364)：2用户（配送三照/申诉举证/机主申报） 1管理端（工单处理证据）。员工ID与用户ID数值可能相同，缺本列则同内容同用途会跨身份空间撞媒体键',
  `MEDIA_PURPOSE`    tinyint      NOT NULL COMMENT '用途：1签收三照 2申诉举证 3异常举证 4工单证据（E2E-05：申报照片由机主登记、处理证据由员工登记）。跨用途引用一律拒绝',
  `CONTENT_SHA256`   char(64)     NOT NULL COMMENT '内容SHA-256（完整性校验元数据；本地适配器落盘后校验）',
  `SIZE_BYTES`       bigint       NOT NULL COMMENT '内容字节数',
  `MIME_TYPE`        varchar(50)  NOT NULL COMMENT 'MIME类型(max50)，仅允许 image/*',
  `BOUND_TASK_ID`    bigint       COMMENT '已绑定业务ID（用途1-3为配送任务ID，用途4为工单ID）；提交时原子占用（WHERE BOUND_TASK_ID IS NULL），防同一照片跨单复用',
  PRIMARY KEY (`ID`),
  -- 媒体键是引用完整性主键：重复登记同内容同用途幂等命中同键，库层唯一防悬空引用
  UNIQUE INDEX `uk_dmedia_key` (`MEDIA_KEY`),
  INDEX `idx_dmedia_owner` (`OWNER_USER_ID`),
  INDEX `idx_dmedia_task` (`BOUND_TASK_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='配送受控媒体表';

-- ----------------------------
-- 自动补货规则表（REQ-011：用户显式配置固定周期，非 AI 预测；
-- 第 n 期订单号由 规则ID+期序 确定性派生，同期重复生成撞 uk_order_no 而幂等，E2E-03 规则19）
-- ----------------------------
DROP TABLE IF EXISTS `ws_delivery_auto_rule`;
CREATE TABLE `ws_delivery_auto_rule` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `RULE_KEY`         varchar(64)  NOT NULL COMMENT '规则创建幂等键(max64)：sha256(userId:requestId)，重复提交命中唯一键不重复建规则',
  `USER_ID`          bigint       NOT NULL COMMENT '规则所属用户ID（ws_user.ID）',
  `CARD_ID`          bigint       NOT NULL COMMENT '扣款水卡ID（ws_card.ID，逐期创单时按当期状态重新校验）',
  `STATION_ID`       bigint       NOT NULL COMMENT '配送水站ID',
  `WATER_TYPE_ID`    bigint       NOT NULL COMMENT '水种ID（ws_water_type.ID）',
  `CONTAINER_SPEC`   varchar(20)  NOT NULL COMMENT '容器规格(max20)',
  `DELIVERY_COUNT`   int          NOT NULL COMMENT '每期配送数量',
  `PLAN_RETURN_COUNT` int         NOT NULL COMMENT '每期计划回收数量',
  `RECEIVE_ADDRESS`  varchar(200) NOT NULL COMMENT '收水地址(max200)',
  `RECEIVE_PHONE`    varchar(20)  NOT NULL COMMENT '收货电话(max20)',
  `INTERVAL_DAYS`    int          NOT NULL COMMENT '固定周期天数(3~90，用户显式配置)',
  `ANCHOR_TIME`      varchar(14)  NOT NULL COMMENT '周期锚点=规则创建时间；第n期到期时间=锚点+n*INTERVAL_DAYS天，期序是幂等键组成部分',
  `RULE_STATUS`      tinyint      NOT NULL COMMENT '规则状态(1355)：1启用 2停用 3已取消（终态不可恢复）',
  PRIMARY KEY (`ID`),
  -- 规则创建幂等的数据库层保证：同 userId+requestId 重复提交不并存第二条规则
  UNIQUE INDEX `uk_dauto_rule_key` (`RULE_KEY`),
  INDEX `idx_dauto_user` (`USER_ID`),
  INDEX `idx_dauto_status` (`RULE_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='自动补货规则表';

-- ----------------------------
-- 字典
-- ----------------------------
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('配送员状态', '1350', '配送员准入状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1350', 1, 1, '待审核');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1350', 2, 2, '启用');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1350', 3, 3, '停用');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1350', 4, 4, '审核驳回');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('配送任务状态', '1351', '配送履约状态机');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1351', 1, 1, '待接单');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1351', 2, 2, '已接单');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1351', 3, 3, '配送中');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1351', 4, 4, '已送达待确认');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1351', 5, 5, '已签收');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1351', 6, 6, '已取消');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1351', 7, 7, '申诉中');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('申诉状态', '1352', '配送申诉处理状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1352', 1, 1, '待处理');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1352', 2, 2, '成立待补偿');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1352', 3, 3, '不成立驳回');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1352', 4, 4, '撤销');
-- E2E-03 规则17：裁决第三种确定结果「补送待执行」（资金补偿走 2，真实退款属 E2E-04）
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1352', 5, 5, '补送待执行');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('签收定位状态', '1353', '配送三照签收定位记录状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1353', 1, 1, '定位已记录');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1353', 2, 2, '定位未记录');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('配送异常原因', '1354', '配送履约异常上报原因');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1354', 1, 1, '联系不上用户');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1354', 2, 2, '地址异常');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1354', 3, 3, '数量问题');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1354', 4, 4, '货物破损');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1354', 5, 5, '其他');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('自动补货规则状态', '1355', '配送自动补货规则启停');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1355', 1, 1, '启用');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1355', 2, 2, '停用');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1355', 3, 3, '已取消');

-- ----------------------------
-- 测试数据（一名启用配送员+一条配送中任务，覆盖列表开发）
-- ----------------------------
INSERT IGNORE INTO `ws_courier` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `USER_ID`, `COURIER_NAME`, `COURIER_PHONE`, `ID_CARD_NO`, `STATION_IDS`, `SERVICE_REGION`, `COURIER_STATUS`, `AUDIT_REMARK`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000', 2, '李配送', '13800001111', NULL, '1,2', '武汉东湖高新区', 2, '光谷片区配送'),
(2, 0, 1, '20260710120000', 1, '20260710120000', 3, '待审核配送员', '13800002222', NULL, NULL, NULL, 1, NULL);

-- 快照口径：ORDER_AMOUNT(3000)=WATER_AMOUNT(2400)+DELIVERY_FEE(600)，历史样例按下单时价格快照保留（10L桶 1200/桶×2 + 历史配送费 300/桶×2）
INSERT IGNORE INTO `ws_delivery_task` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `TASK_NO`, `ORDER_ID`, `USER_ID`, `STATION_ID`, `COURIER_ID`, `WATER_TYPE_ID`, `WATER_TYPE`, `CONTAINER_SPEC`, `DELIVERY_COUNT`, `PLAN_RETURN_COUNT`, `ACTUAL_DELIVERY_COUNT`, `ACTUAL_RETURN_COUNT`, `WATER_AMOUNT`, `DELIVERY_FEE`, `RECEIVE_ADDRESS`, `RECEIVE_PHONE`, `TASK_STATUS`, `VERSION`, `SCHEDULED_TIME`, `ACCEPT_TIME`, `DEPART_TIME`, `ARRIVE_TIME`, `SIGN_TIME`, `SIGN_PHOTOS`, `LOCATION_STATUS`, `APPEAL_DEADLINE`, `TASK_REMARK`) VALUES
(1, 0, 1, '20260710150000', 1, '20260710153000', 'DT-DEMO-2001', 3, 1, 1, 1, 1, '纯净水', '10L桶', 2, 1, NULL, NULL, 2400, 600, '光谷软件园 A1 栋 502', '13900001111', 3, 3, NULL, '20260710151000', '20260710153000', NULL, NULL, NULL, NULL, NULL, '联调样例：配送中');

-- ----------------------------
-- 菜单与权限统一由 03-demo-baseline.sql 建立
-- ----------------------------



-- ============================================================
-- 六维达康 · 站内消息域（ws_message，E2E-03 包A / REQ-059）
-- 字典段：1312 站内消息领域、1313 站内消息发送状态
-- 说明：一期只做站内消息（in-app），不接微信订阅消息；配送履约节点
--      （任务生成/接单/离站/送达/签收/申诉）与业务动作同事务落一条，
--      SEND_TIME 与动作时间同源（E2E-03 规则13 同源要求的消息侧）。
-- ============================================================
DROP TABLE IF EXISTS `ws_message`;
CREATE TABLE `ws_message` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `USER_ID`          bigint       NOT NULL COMMENT '收件用户ID（ws_user.ID）；查询必须按会话用户强制过滤（铁律6）',
  `MSG_DOMAIN`       tinyint      NOT NULL COMMENT '消息领域(1312)：1取水 2卡券 3配送 4机主 5系统',
  `MSG_TITLE`        varchar(100) NOT NULL COMMENT '标题(max100)',
  `MSG_CONTENT`      varchar(500) NOT NULL COMMENT '正文(max500)，不得包含明文手机号等敏感信息',
  `MSG_CHANNEL`      tinyint      NOT NULL COMMENT '渠道：1站内（一期唯一真实发送渠道）；2微信订阅（E2E-07 骨架占位，仅重试/降级状态机与历史样本用，一期不真实发送）',
  `SEND_STATUS`      tinyint      NOT NULL COMMENT '发送状态(1313)：1待发送 2发送中 3发送失败 4已送达（站内消息落库即送达=4）',
  `SEND_TIME`        varchar(14)  COMMENT '发送时间（与触发它的业务动作时间同源）',
  `READ_FLAG`        tinyint      NOT NULL DEFAULT 0 COMMENT '已读：0未读 1已读',
  `OBJECT_TYPE`      varchar(20)  COMMENT '关联对象类型(max20)：order/task/appeal/device/service',
  `OBJECT_ID`        varchar(64)  COMMENT '关联对象业务键(max64)：订单号/任务号/申诉ID',
  PRIMARY KEY (`ID`),
  INDEX `idx_message_user_time` (`USER_ID`, `SEND_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='站内消息表';

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('站内消息领域', '1312', '站内消息业务领域');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1312', 1, 1, '取水');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1312', 2, 2, '卡券');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1312', 3, 3, '配送');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1312', 4, 4, '机主');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1312', 5, 5, '系统');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('站内消息发送状态', '1313', '站内消息发送状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1313', 1, 1, '待发送');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1313', 2, 2, '发送中');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1313', 3, 3, '发送失败');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1313', 4, 4, '已送达');


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
  `ACTIVE_DEDUPE_KEY` varchar(100) COMMENT '活动告警幂等键：AlarmDedupeKey 单一出处生成（类型:设备[:来源]）。活动期间非空且全库唯一——并发触发撞键读回原告警而不是插第二条；忽略/自动恢复时原子清空，历史终态告警因此可重复留存（MySQL 可空唯一不约束 NULL）',
  `HANDLE_BY`        bigint       COMMENT '处置人（api_employee.ID；忽略/转工单时回填）',
  `HANDLE_TIME`      varchar(14)  COMMENT '处置时间',
  PRIMARY KEY (`ID`),
  -- 「相同设备+类型+来源最多一条活动告警」的物理保证。没有它，离线扫描/心跳/指令超时
  -- 并发命中同一设备时会插出重复 PENDING 告警——先COUNT再INSERT在并发下必然穿透。
  UNIQUE INDEX `uk_alarm_active_dedupe` (`ACTIVE_DEDUPE_KEY`),
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
  `ORDER_NO`         varchar(32)  NOT NULL COMMENT '工单号(max32)，系统生成',
  `WORK_TYPE`        tinyint      NOT NULL COMMENT '工单类型(1365)：1维修 2配件 3巡检',
  `DEVICE_ID`        bigint       COMMENT '设备ID（可空：非设备类工单）',
  `SOURCE_TYPE`      tinyint      NOT NULL COMMENT '来源(1366沿用旧口径)：1告警转入 2机主申报 3后台创建',
  `ALARM_ID`         bigint       COMMENT '来源告警ID；一个告警最多转一个工单（uk_wo_alarm）',
  `APPLICANT_USER_ID` bigint      COMMENT '申报人（ws_user.ID）；机主申报时回填，身份只取 KH_USER 会话',
  `REQUEST_ID`       varchar(64)  COMMENT '机主申报幂等键；重复提交返回原工单（uk_wo_request，可空唯一）',
  `ORDER_TITLE`      varchar(100) NOT NULL COMMENT '工单标题(max100)',
  `ORDER_CONTENT`    varchar(1000) COMMENT '问题描述(max1000)',
  `ORDER_PHOTOS`     text         COMMENT '证据媒体键JSON数组（受控 mediaKey，复用 ws_delivery_media，不存本机路径或URL）',
  `ASSIGNEE_ID`      bigint       COMMENT '处理人（api_employee.ID，分配后回填）',
  `ORDER_STATUS`     tinyint      NOT NULL COMMENT '工单状态(1362)：1待确认 2待分配 3处理中 4待复核 5已关闭 6已驳回',
  `ASSIGN_TIME`      varchar(14)  COMMENT '分配时间',
  `FINISH_TIME`      varchar(14)  COMMENT '处理提交时间（转待复核时回填）',
  `FINISH_RESULT`    varchar(500) COMMENT '处理结果(max500)',
  `RESULT_PHOTOS`    text         COMMENT '处理证据媒体键JSON数组（受控 mediaKey；员工身份登记，OWNER_PORTAL=1）',
  `REVIEW_BY`        bigint       COMMENT '复核人（api_employee.ID）',
  `REVIEW_TIME`      varchar(14)  COMMENT '复核时间（通过或退回都回填）',
  `REVIEW_REMARK`    varchar(500) COMMENT '复核意见(max500)；复核退回时必填',
  `REJECT_REASON`    varchar(500) COMMENT '驳回原因(max500)；待确认→已驳回时必填',
  `CLOSE_TIME`       varchar(14)  COMMENT '关闭时间（复核通过后回填）',
  `VERSION`          int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本；全部状态迁移经 VERSION+前态 CAS，影响行数必须为1',
  PRIMARY KEY (`ID`),
  -- 工单号是三端贯穿的业务键，唯一约束防并发建单重号（原为普通索引+代码查重，并发可穿透）
  UNIQUE INDEX `uk_wo_no` (`ORDER_NO`),
  -- 一个告警最多转一个工单：转单并发重放撞键读回原工单（可空唯一，非告警来源不参与）
  UNIQUE INDEX `uk_wo_alarm` (`ALARM_ID`),
  -- 机主重复提交同一申报返回原工单（可空唯一，告警/后台来源不参与）
  UNIQUE INDEX `uk_wo_request` (`REQUEST_ID`),
  INDEX `idx_wo_device` (`DEVICE_ID`),
  INDEX `idx_wo_assignee_status` (`ASSIGNEE_ID`, `ORDER_STATUS`),
  INDEX `idx_wo_applicant` (`APPLICANT_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='运维工单表（E2E-05：告警转单/机主申报/PC巡检统一模型，六状态经 CAS 迁移）';

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
  `ACTOR_ID`         bigint       NULL COMMENT '动作发起人ID（系统动作为哨兵值）',
  `ACTOR_PORTAL`     tinyint      NULL COMMENT '发起端(1376)：1公司后台 2用户端 3机主端 4配送端 5渠道端 6系统 7设备',
  `ACTOR_ROLE`       varchar(20)  NULL COMMENT '发起人角色快照(max20)',
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

-- 工单状态六值（E2E-05 冻结口径）。原四状态（待派工/处理中/已完成/已关闭）在工单 Java 层
-- 落地前重定义——机主申报需要「待确认/已驳回」入口态，处理结果需要「待复核」质检态。
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('工单状态', '1362', '运维工单状态机（六状态）');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1362', 1, 1, '待确认');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1362', 2, 2, '待分配');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1362', 3, 3, '处理中');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1362', 4, 4, '待复核');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1362', 5, 5, '已关闭');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1362', 6, 6, '已驳回');

-- E2E-05 新增字典：工单类型/批量范围/批次聚合状态/SIM状态（选号避开 1360-1364 与售后段 1370-1375）
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('工单类型', '1365', '运维工单类型');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1365', 1, 1, '维修');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1365', 2, 2, '配件');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1365', 3, 3, '巡检');
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('批量指令范围', '1366', '批量设备控制的目标范围类型');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1366', 1, 1, '指定设备');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1366', 2, 2, '指定水站');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1366', 3, 3, '全部设备');
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('批量指令聚合状态', '1367', '批量任务的聚合执行状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1367', 1, 1, '处理中');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1367', 2, 2, '全部成功');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1367', 3, 3, '部分成功');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1367', 4, 4, '全部失败');
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('SIM状态', '1368', '设备SIM卡运维状态（档案维护/模拟器口径，真实运营商查询未接入）');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1368', 1, 1, '正常');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1368', 2, 2, '未激活');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1368', 3, 3, '欠费');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1368', 4, 4, '停用');

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('事件类型', '1363', '领域事件类型（n8n白名单订阅源）');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 1, 1, '订单状态变化');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 2, 2, '设备状态变化');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 3, 3, '告警产生');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 4, 4, '工单状态变化');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 5, 5, '配送节点变化');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 6, 6, '支付结果');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 7, 7, '分账结果');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 8, 8, '指令状态变化');
-- 10告警状态变化：忽略/自动恢复/转工单的处置轨迹（3告警产生 只表达创建）；9售后动作 由 E2E-04 迁移写入
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 10, 10, '告警状态变化');

-- ----------------------------
-- 测试数据（一条严重告警已转工单 + 对应处理中工单，演示告警→工单闭环）
-- ----------------------------
-- 告警1（已转工单）与告警2（活动离线告警）：活动告警必须带 ACTIVE_DEDUPE_KEY（键法与
-- AlarmDedupeKey 单一出处一致：类型:设备[:来源]）；已转工单的告警活动键仍保留（任务书 3.2：
-- 转单后活动键保留到告警恢复或工单正式关闭）。
INSERT IGNORE INTO `ws_alarm` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DEVICE_ID`, `ALARM_TYPE`, `ALARM_LEVEL`, `ALARM_CONTENT`, `SOURCE_REF`, `ALARM_STATUS`, `WORK_ORDER_ID`, `RECOVER_TIME`, `ACTIVE_DEDUPE_KEY`, `HANDLE_BY`, `HANDLE_TIME`) VALUES
(1, 0, 1, '20260710160000', 1, '20260710161000', 2, 2, 3, '设备上报严重故障 E003 流量计异常，已自动阻断下单', 'E003', 2, 1, NULL, '2:2:E003', 1, '20260710161000'),
(2, 0, 1, '20260710170000', 1, '20260710170000', 2, 1, 2, '设备心跳超时 10 分钟，判定离线', NULL, 1, NULL, NULL, '1:2', NULL, NULL);

INSERT IGNORE INTO `ws_work_order` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `ORDER_NO`, `WORK_TYPE`, `DEVICE_ID`, `SOURCE_TYPE`, `ALARM_ID`, `APPLICANT_USER_ID`, `REQUEST_ID`, `ORDER_TITLE`, `ORDER_CONTENT`, `ORDER_PHOTOS`, `ASSIGNEE_ID`, `ORDER_STATUS`, `ASSIGN_TIME`, `FINISH_TIME`, `FINISH_RESULT`, `RESULT_PHOTOS`, `REVIEW_BY`, `REVIEW_TIME`, `REVIEW_REMARK`, `REJECT_REASON`, `CLOSE_TIME`, `VERSION`) VALUES
(1, 0, 1, '20260710161000', 1, '20260710162000', 'GD20260710161000001', 1, 2, 1, 1, NULL, NULL, '南湖1号机流量计异常', '告警自动转入：E003 流量计异常，需现场检修', NULL, 1, 3, '20260710162000', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1);

-- ----------------------------
-- 菜单与权限统一由 03-demo-baseline.sql 建立
-- ----------------------------

-- ----------------------------
-- 售后执行动作表（E2E-04 包A）
--
-- 与 deploy/mysql/migrations/2026-07-29-aftersale-e2e04-a.sql 是同一份 schema 的两个投放口：
-- 迁移供**已有库**升级（本文件只在空数据卷首次初始化时执行，已有环境永不重跑），
-- 本段供**全新环境** docker compose up 一次建齐。两处必须逐列一致，改一处要改两处。
--
-- 这不是可选项：server/src/main/resources/mapper/trade/WsOrderMapper.xml 的
-- Admin_Order_Columns 片段带一条**无条件**的 EXISTS(... ws_after_sale_action ...)，
-- 而该片段被订单中心列表 pageAdminOrders 与追溯抽屉 selectAdminOrderById 共同 include。
-- 本表缺席时报错的不是售后模块，是已验收的订单中心整体 1146 Table doesn't exist。
-- 各列与索引的设计理由写在迁移脚本里，不在此重复。
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_after_sale_action` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间',
  `AFTER_SALE_NO`       varchar(32)  NOT NULL COMMENT '售后号：AS + sha256(sourceType:sourceId) 截断，确定性派生（同来源重放得同号）',
  `SOURCE_TYPE`         tinyint      NOT NULL COMMENT '售后来源(1370)：1配送取消 2配送申诉 3取水异常核账',
  `SOURCE_ID`           bigint       NOT NULL COMMENT '来源主体ID：1取ws_order.ID 2取ws_delivery_appeal.ID 3取ws_order.ID',
  `ORDER_ID`            bigint       NOT NULL COMMENT '关联订单ID（额度聚合与账本核验的锚点）',
  `USER_ID`             bigint       NOT NULL COMMENT '订单归属用户ID',
  `CARD_ID`             bigint       NULL COMMENT '返还目标卡ID；首次购卡未入账退款尚未发卡时可空，其它返还必须非空',
  `ACTION_TYPE`         tinyint      NOT NULL COMMENT '动作类型(1371)：1卡内退款 2卡内补偿 3支付机构退款(包B) 4补送(包C)',
  `STRATEGY_CODE`       varchar(20)  NULL COMMENT '补偿策略码：PRODUCT_ONLY/SERVICE_FEE_ONLY/PRODUCT_AND_SERVICE/RESEND/REJECT；单一出处见 AfterSaleEnum.StrategyCode',
  `APPROVED_COUNT`      int          NULL COMMENT '运营批准的受影响数量（桶）；取消与取水核账为NULL',
  `REFUND_PRODUCT_FEN`  bigint       NOT NULL DEFAULT 0 COMMENT '水品权益返还金额(分)，payWay=2 专用；额度锚点=快照 waterAmountFen',
  `REFUND_SERVICE_FEN`  bigint       NOT NULL DEFAULT 0 COMMENT '配送费返还金额(分)；额度锚点=快照 deliveryFeeFen。与水品分列是为了两维独立封顶',
  `REFUND_PRODUCT_ML`   bigint       NOT NULL DEFAULT 0 COMMENT '水品权益返还水量(毫升)，payWay=3 专用；额度锚点=快照 waterMl',
  `REFUND_AMOUNT`       bigint       NOT NULL DEFAULT 0 COMMENT '返还金额合计(分)=REFUND_PRODUCT_FEN+REFUND_SERVICE_FEN，供CAS与流水使用；封顶判定不看本列',
  `CALC_SNAPSHOT`       text         NULL COMMENT '服务端计算依据快照（策略码、数量边界、额度锚点、已用额度、单价来源），出账即冻结，事后不重算',
  `ACTION_STATUS`       tinyint      NOT NULL COMMENT '执行状态(1372)：1待执行 2执行中 3已完成 4可重试 5需人工对账 6已终止',
  `VERSION`             int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，状态机CAS的前态条件之一',
  `RETRY_COUNT`         int          NOT NULL DEFAULT 0 COMMENT '重试次数，达上限转需人工对账',
  `NEXT_RETRY_TIME`     varchar(14)  NULL COMMENT '下次可重试时间，Worker 预筛条件',
  `REFUND_ID`           bigint       NULL COMMENT '支付机构退款单ID（包B），卡内返还恒为NULL',
  `RESULT_ORDER_ID`     bigint       NULL COMMENT '补送产生的子订单ID（包C）',
  `RESULT_TASK_ID`      bigint       NULL COMMENT '补送产生的任务ID（包C）',
  `APPROVE_BY`          bigint       NULL COMMENT '批准人（PC员工ID）；用户自助取消为NULL',
  `APPROVE_TIME`        varchar(14)  NULL COMMENT '批准时间',
  `FINISH_TIME`         varchar(14)  NULL COMMENT '终态时间（完成/终止/转人工）',
  `LAST_ERROR`          varchar(500) NULL COMMENT '最近一次失败原因，转人工对账时的排查依据',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_after_sale_no` (`AFTER_SALE_NO`),
  UNIQUE INDEX `uk_after_sale_source` (`SOURCE_TYPE`, `SOURCE_ID`),
  INDEX `idx_after_sale_order` (`ORDER_ID`, `ACTION_STATUS`),
  INDEX `idx_after_sale_status` (`ACTION_STATUS`, `NEXT_RETRY_TIME`),
  INDEX `idx_after_sale_card` (`CARD_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='售后执行动作表（E2E-04 包A）';

-- 售后字典 1370/1371/1372。本文件用 INSERT IGNORE 即可：只在空数据卷跑一次，
-- 不存在迁移脚本那种「重复执行翻倍」的问题，写法与本文件其余字典保持一致。
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('售后来源', '1370', '售后执行动作的触发来源');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1370', 1, 1, '配送取消');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1370', 2, 2, '配送申诉');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1370', 3, 3, '取水异常核账');
-- 包D-5：充值退款必须独立成一个来源码——SOURCE_ID 与取水核账同为 ws_order.ID，
-- 复用同一个码会让两类订单在 ID 相同时互相占 uk_after_sale_source
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1370', 4, 4, '充值退款');
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('售后动作类型', '1371', '售后执行动作的资金/履约形态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1371', 1, 1, '卡内退款');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1371', 2, 2, '卡内补偿');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1371', 3, 3, '机构退款');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1371', 4, 4, '补送');
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('售后执行状态', '1372', '售后执行动作状态机');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1372', 1, 1, '待执行');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1372', 2, 2, '执行中');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1372', 3, 3, '已完成');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1372', 4, 4, '可重试');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1372', 5, 5, '需人工对账');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1372', 6, 6, '已终止');

-- 事件类型 1363 补第 9 项：三条售后来源的 eventKey 各不相同，复用「配送节点」会让
-- 取水核账事件挂错类型、按类型检索不出来。对应 OpsEnum.EventType.AFTER_SALE。
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1363', 9, 9, '售后动作');

-- 退款来源 1373（E2E-04 包B）。页面据此显示「Refund-Sim」而不是「微信退款」——
-- 把模拟退款显示成微信退款，会让运营以为钱已经原路退回用户账户。
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('退款来源', '1373', '支付机构退款的来源；页面据此显示 Refund-Sim');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1373', 1, 1, '微信');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1373', 2, 2, 'Refund-Sim');

-- 退款状态 1343 补两档：没有它们，一笔卡在重试或需人工核对的退款只能记成「退款失败」，
-- 而失败与待重试对运营是两种完全不同的处置（一个等系统重试，一个必须人工介入）。
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1343', 4, 4, '待重试');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1343', 5, 5, '需人工对账');

-- ----------------------------
-- 权益批次模型（E2E-04 包D，REQ-061）
--
-- 与 deploy/mysql/migrations/2026-07-29-aftersale-e2e04-d.sql 是同一份 schema 的两个投放口：
-- 迁移供已有库升级，本段供全新环境首次初始化。两处必须逐列一致（SchemaParityTest 常态守住）。
--
-- 存在理由：完整购卡/充值退款不能用 ws_card 的聚合余额去猜。聚合值只回答「现在还剩多少」，
-- 回答不了「这笔要退的充值，它带来的权益被用掉了多少」——同卡多笔充值时按聚合值退款必然错。
-- ----------------------------
DROP TABLE IF EXISTS `ws_card_entitlement_batch`;
CREATE TABLE `ws_card_entitlement_batch` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间；同到期时间时的批次选取次序依据',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间',

  `CARD_ID`             bigint       NOT NULL COMMENT '所属水卡ID',
  `USER_ID`             bigint       NOT NULL COMMENT '卡主用户ID（分摊与退款的归属核验用）',
  `SOURCE_TYPE`         tinyint      NOT NULL COMMENT '批次来源(1375)：1首次购卡 2已有卡充值 3历史聚合（不可退）',
  `ORDER_ID`            bigint       NULL COMMENT '来源充值订单ID；历史聚合批次为NULL',
  `ORDER_NO`            varchar(32)  NULL COMMENT '来源充值订单号；历史聚合批次为NULL',
  `PAYMENT_ID`          bigint       NULL COMMENT '来源支付单ID；退款金额封顶按它聚合',
  `PACKAGE_ID`          bigint       NULL COMMENT '套餐ID',
  `PACKAGE_SNAP`        text         NULL COMMENT '套餐快照：折算公式的 payAmountFen / grantedWaterMl 只能取自这里，不查当前套餐',

  `PAY_AMOUNT_FEN`      bigint       NOT NULL DEFAULT 0 COMMENT '本批次对应的实付金额(分)；退款折算的分子基准',
  `GRANT_AMOUNT_FEN`    bigint       NOT NULL DEFAULT 0 COMMENT '本批次发放的余额权益(分)=本金+赠送',
  `GRANT_BONUS_FEN`     bigint       NOT NULL DEFAULT 0 COMMENT '其中赠送部分(分)；赠送已消费部分不退款',
  `GRANT_WATER_ML`      bigint       NOT NULL DEFAULT 0 COMMENT '本批次发放的水量权益(毫升)',
  `REMAIN_AMOUNT_FEN`   bigint       NOT NULL DEFAULT 0 COMMENT '剩余余额权益(分)；分摊与冲正的 CAS 前态',
  `REMAIN_WATER_ML`     bigint       NOT NULL DEFAULT 0 COMMENT '剩余水量权益(毫升)；同上',

  `EXPIRE_TIME`         varchar(14)  NULL COMMENT '本批次有效期；NULL=永久（D-213 付费卡永久）。批次选取按它升序，NULL 视为最晚',
  `SCOPE_JSON`          text         NULL COMMENT '本批次的可用范围快照',

  `BATCH_STATUS`        tinyint      NOT NULL COMMENT '批次状态(1374)：1可用 2退款锁定 3已退款 4已耗尽 5已过期 6不可退',
  `REFUND_LOCKED_BY`    bigint       NULL COMMENT '锁定该批次的售后动作ID；退款处理中禁止继续消费',
  `REFUND_LOCK_TIME`    varchar(14)  NULL COMMENT '锁定时间',
  `REFUNDED_AMOUNT_FEN` bigint       NOT NULL DEFAULT 0 COMMENT '已退款金额(分)；累计封顶基准之一',
  `VERSION`             int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，分摊与冲正 CAS 的前态条件',

  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_batch_order` (`ORDER_ID`),
  INDEX `idx_batch_pick` (`CARD_ID`, `BATCH_STATUS`, `EXPIRE_TIME`, `CREATE_TIME`),
  INDEX `idx_batch_payment` (`PAYMENT_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='水卡权益批次表（E2E-04 包D，REQ-061）';

DROP TABLE IF EXISTS `ws_entitlement_allocation`;
CREATE TABLE `ws_entitlement_allocation` (
  `ID`               bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：分摊是账本事实，保持0',
  `CREATE_BY`        bigint      NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14) NOT NULL COMMENT '更新时间',

  `BATCH_ID`         bigint      NOT NULL COMMENT '被扣减的权益批次ID',
  `CARD_ID`          bigint      NOT NULL COMMENT '水卡ID（冗余，便于按卡对账不必回表批次）',
  `FLOW_ID`          bigint      NULL COMMENT '对应的钱包流水ID',
  `ORDER_ID`         bigint      NULL COMMENT '触发消费的订单ID',
  `BIZ_KEY`          varchar(64) NOT NULL COMMENT '消费业务幂等键（如 DELIVERY:<orderNo>、DISPENSE:<orderNo>）',
  `ALLOC_SEQ`        int         NOT NULL DEFAULT 1 COMMENT '同一次消费跨多个批次时的分摊序号，从1开始',

  `ALLOC_AMOUNT_FEN` bigint      NOT NULL DEFAULT 0 COMMENT '本次从该批次扣减的余额(分)，正数',
  `ALLOC_WATER_ML`   bigint      NOT NULL DEFAULT 0 COMMENT '本次从该批次扣减的水量(毫升)，正数',
  `REVERSED_FLAG`    tinyint     NOT NULL DEFAULT 0 COMMENT '是否已被冲正：0否 1是；退款冲正只冲未冲正的分摊',

  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_alloc_biz_batch` (`BIZ_KEY`, `BATCH_ID`),
  INDEX `idx_alloc_batch` (`BATCH_ID`, `REVERSED_FLAG`),
  INDEX `idx_alloc_card` (`CARD_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='权益批次消费分摊表（E2E-04 包D，REQ-061）';

-- 权益批次字典 1374/1375（E2E-04 包D）
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('权益批次状态', '1374', '水卡权益批次的生命周期状态');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1374', 1, 1, '可用');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1374', 2, 2, '退款锁定');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1374', 3, 3, '已退款');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1374', 4, 4, '已耗尽');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1374', 5, 5, '已过期');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1374', 6, 6, '不可退');
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`) VALUES ('权益批次来源', '1375', '权益批次的产生来源');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1375', 1, 1, '首次购卡');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1375', 2, 2, '已有卡充值');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`) VALUES (NULL, 1, '1375', 3, 3, '历史聚合');
-- 1375 值 4（运营赠卡）由 2026-07-31-settlement-e2e08-a.sql 增补：e2e04-d 迁移的防重前置
-- 断言本 init 恰有 3 值，值 4 只能走迁移轨（文件名序在其后），两轨最终收敛为 4 值。

-- ----------------------------
-- 演示水卡的历史聚合权益批次（E2E-04 包D-4，REQ-061）
--
-- 【为什么 init 里必须有这两行】
-- D-4 之后，取水与配送的扣减在同事务内要把额度摊到具体批次上；凑不出额度就整笔回滚。
-- 上面 ws_card 里的两张演示卡是直接 INSERT 出来的，没有经过购卡/充值入账，
-- 因此不带任何批次。缺了下面两行，全新环境（compose 只执行 init、从不执行 migrations）
-- 一开机就是「演示卡余额看得见、一分也扣不动」，而错因显示为「批次剩余不足」——
-- 看起来像代码 Bug，其实是种子数据缺行。
--
-- 形态与 migrations/2026-07-29-aftersale-e2e04-d2-legacy-backfill.sql 逐列一致：
-- SOURCE_TYPE=3历史聚合、BATCH_STATUS=6不可退、PAY_AMOUNT_FEN=0、ORDER_ID/PAYMENT_ID 为 NULL。
-- 这部分权益对应哪一笔付款、付了多少，账本里没有答案，猜一个填进去就是凭空造出可退款基准。
-- 剩余额度恰等于卡的聚合值，满足「逐卡批次剩余合计 == 卡聚合值」这条不变式。
-- ----------------------------
INSERT IGNORE INTO `ws_card_entitlement_batch`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`,
 `CARD_ID`, `USER_ID`, `SOURCE_TYPE`, `ORDER_ID`, `ORDER_NO`, `PAYMENT_ID`, `PACKAGE_ID`, `PACKAGE_SNAP`,
 `PAY_AMOUNT_FEN`, `GRANT_AMOUNT_FEN`, `GRANT_BONUS_FEN`, `GRANT_WATER_ML`,
 `REMAIN_AMOUNT_FEN`, `REMAIN_WATER_ML`, `EXPIRE_TIME`, `SCOPE_JSON`,
 `BATCH_STATUS`, `REFUND_LOCKED_BY`, `REFUND_LOCK_TIME`, `REFUNDED_AMOUNT_FEN`, `VERSION`) VALUES
(1, 0, 1, '20260710120000', 1, '20260710120000',
 1, 1, 3, NULL, NULL, NULL, NULL, NULL,
 0, 5500, 0, 495000, 5500, 495000, NULL,
 '{"scopeType":"specified","stationIds":[1],"stationNames":["光谷软件园水站"]}',
 6, NULL, NULL, 0, 1),
(2, 0, 1, '20260710120000', 1, '20260710120000',
 2, 1, 3, NULL, NULL, NULL, NULL, NULL,
 0, 0, 0, 100000, 0, 100000, NULL, NULL,
 6, NULL, NULL, 0, 1);

-- ----------------------------
-- 用户配送地址簿（2026-08-02 家庭/地址链路落地：此前仅前端本机存储，无服务端）。
-- 展示一律经 PhoneMask 脱敏；配送下单可传 addressId 由服务端解引用取号写任务快照，
-- 号码不经前端回流（铁律6）。同用户 IS_DEFAULT=1 至多一条，由服务层事务互斥保证。
-- ----------------------------
DROP TABLE IF EXISTS `ws_user_address`;
CREATE TABLE `ws_user_address` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间',
  `USER_ID`             bigint       NOT NULL COMMENT '归属用户（ws_user.ID）；读写恒以会话人过滤',
  `CONTACT_NAME`        varchar(30)  NOT NULL COMMENT '联系人姓名(max30)',
  `CONTACT_PHONE`       varchar(11)  NOT NULL COMMENT '联系电话；展示必须经 PhoneMask 脱敏，不回流前端',
  `REGION`              varchar(100) NOT NULL COMMENT '省市区(max100)',
  `ADDRESS_DETAIL`      varchar(200) NOT NULL COMMENT '详细地址(max200)',
  `IS_DEFAULT`          tinyint      NOT NULL DEFAULT 0 COMMENT '默认地址：1是 0否；同用户至多一条为1（服务层互斥）',
  `LOCATION_AUTHORIZED` tinyint      NOT NULL DEFAULT 0 COMMENT '是否已授权定位取点：1是 0否',
  PRIMARY KEY (`ID`),
  INDEX `idx_uaddr_user` (`USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户配送地址簿';

-- ----------------------------
-- 家庭资料（自愿填写；隐私同意时间为必填审计位，首次保存记服务端时间后不变）。
-- ----------------------------
DROP TABLE IF EXISTS `ws_family_profile`;
CREATE TABLE `ws_family_profile` (
  `ID`                   bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`          tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`            bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`          varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`            bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`          varchar(14)  NOT NULL COMMENT '更新时间',
  `USER_ID`              bigint       NOT NULL COMMENT '归属用户（ws_user.ID）；一人一份',
  `PRIVACY_CONSENT_TIME` varchar(14)  NOT NULL COMMENT '隐私说明同意时间（首次保存记服务端时间，后续更新不改写）',
  `MEMBER_COUNT`         int          NULL COMMENT '家庭人数（自愿）',
  `WATER_HABIT_NOTE`     varchar(200) NULL COMMENT '用水习惯备注(max200，自愿)',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_family_user` (`USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='家庭资料（自愿信息）';


-- ============================================================
-- 审计导出任务（B23 / REQ-024、REQ-066）——与 server/sql/ws_audit_export.sql 逐字同源
-- 文件生成待对象存储接入：任务只能停在 1待生成，平台不得自行推进到 4已生成
-- ============================================================
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

-- ============================================================
-- 六维达康 · 小程序入口与运营配置域（ws_mini_entry，S6）
-- 表：ws_mini_entry_config
-- 字典段：1384 入口类型、1385 跳转类型、1386 配置状态
-- 需求映射：REQ-086 入口配置（B21）——只做配置底座，不开发商城/健康服务/广告业务
-- 安全口径：
--   1) 小程序只读「已发布」版本；草稿/已撤回不下发
--   2) 内部路由必须命中路由编号白名单（路由合同+能力守卫仍在前端 goTo 执行）
--   3) 外链仅 https 且域名在配置白名单（默认空=全拒绝）；javascript:/任意 scheme 禁止
--   4) 固定 Tabbar 与账号能力权限不受配置覆盖
-- ============================================================

DROP TABLE IF EXISTS `ws_mini_entry_config`;
CREATE TABLE `ws_mini_entry_config` (
  `ID`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`   tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`     bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`   varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`     bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`   varchar(14)  NOT NULL COMMENT '更新时间',
  `ENTRY_KEY`     varchar(50)  NOT NULL COMMENT '入口键(max50)：功能入口=路由编号(U07/U08/U10/U13/U16等)；内容位=protocol/faq/service/notice',
  `ENTRY_TYPE`    tinyint      NOT NULL COMMENT '入口类型(1384)：1功能入口 2内容链接 3维护公告',
  `ENTRY_NAME`    varchar(50)  NOT NULL COMMENT '展示名称(max50)',
  `SORT_NO`       int          NOT NULL DEFAULT 0 COMMENT '排序（小程序按此升序稳定排列）',
  `ENABLED_FLAG`  tinyint      NOT NULL DEFAULT 2 COMMENT '是否启用(1)：1否 2是；停用或未发布的入口小程序默认隐藏',
  `JUMP_TYPE`     tinyint      COMMENT '跳转类型(1385)：1内部路由 2外部链接；维护公告无跳转可空',
  `ROUTE_ID`      varchar(10)  COMMENT '内部路由编号（服务端白名单+前端路由合同双重校验）',
  `EXTERNAL_URL`  varchar(500) COMMENT '外部链接(max500)：仅 https 且域名在白名单，禁 javascript:/任意 scheme',
  `CONTENT_TEXT`  varchar(500) COMMENT '内容文本(max500)：维护公告正文',
  `CONFIG_STATUS` tinyint      NOT NULL COMMENT '配置状态(1386)：1草稿 2已发布 3已撤回；小程序只读已发布',
  `PUBLISH_TIME`  varchar(14)  COMMENT '最近发布时间',
  `VERSION`       int          NOT NULL DEFAULT 1 COMMENT '乐观锁：状态流转与内容修改的 CAS 前态（并发发布只有一个有效版本）',
  PRIMARY KEY (`ID`),
  -- 同一入口键恒一行：发布/撤回就地流转，绝不产生同键多版本并存
  UNIQUE KEY `uk_mini_entry_key` (`ENTRY_KEY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='小程序入口与运营配置表（S6 配置底座）';

-- ----------------------------
-- 字典（无业务唯一键的字典表：幂等必须 NOT EXISTS，见 mysql8 skill）
-- ----------------------------
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (SELECT '小程序入口类型' AS DICT_NAME, '1384' AS DICT_TYPE, '功能入口/内容链接/维护公告' AS DICT_REMARK) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1384' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '功能入口' AS DICT_LABEL
  UNION ALL SELECT '1384', 2, 2, '内容链接'
  UNION ALL SELECT '1384', 3, 3, '维护公告'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (SELECT '小程序入口跳转类型' AS DICT_NAME, '1385' AS DICT_TYPE, '内部路由/外部链接' AS DICT_REMARK) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1385' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '内部路由' AS DICT_LABEL
  UNION ALL SELECT '1385', 2, 2, '外部链接'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (SELECT '小程序入口配置状态' AS DICT_NAME, '1386' AS DICT_TYPE, '草稿/已发布/已撤回' AS DICT_REMARK) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1386' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '草稿' AS DICT_LABEL
  UNION ALL SELECT '1386', 2, 2, '已发布'
  UNION ALL SELECT '1386', 3, 3, '已撤回'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

-- ----------------------------
-- 测试数据（既有首页宫格四入口的默认配置：全部已发布，行为与硬编码基线一致）
-- ----------------------------
INSERT IGNORE INTO `ws_mini_entry_config`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`ENTRY_KEY`,`ENTRY_TYPE`,`ENTRY_NAME`,`SORT_NO`,`ENABLED_FLAG`,`JUMP_TYPE`,`ROUTE_ID`,`EXTERNAL_URL`,`CONTENT_TEXT`,`CONFIG_STATUS`,`PUBLISH_TIME`,`VERSION`)
VALUES
(1,0,1,'20260807120000',1,'20260807120000','U07',1,'附近水站',10,2,1,'U07',NULL,NULL,2,'20260807120000',1),
(2,0,1,'20260807120000',1,'20260807120000','U08',1,'配送订水',20,2,1,'U08',NULL,NULL,2,'20260807120000',1),
(3,0,1,'20260807120000',1,'20260807120000','U10',1,'充值',30,2,1,'U10',NULL,NULL,2,'20260807120000',1),
(4,0,1,'20260807120000',1,'20260807120000','U13',1,'家庭资料',40,2,1,'U13',NULL,NULL,2,'20260807120000',1),
(5,0,1,'20260807120000',1,'20260807120000','notice',3,'维护公告',0,1,NULL,NULL,NULL,'系统维护期间部分功能暂不可用',1,NULL,1);

-- ----------------------------
-- 分润冲减事实表（D-420 R1）：每(售后动作,分账行)一行冲减事实，两段式——
-- 登记与客户退款成功同事务（outbox 语义：退款成功即事实存在，绝不因冲减失败回滚）；
-- 执行独立事务可重试可转人工。多次部分退款=多个 ACTION 各自成行、同行累计；
-- uk(ACTION_ID,SPLIT_ID) 保证同一动作对同一分账行恒零重复。
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_split_clawback` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：冲减事实必须保持0，不做业务逻辑删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `ACTION_ID`        bigint       NOT NULL COMMENT '售后动作ID（ws_after_sale_action.ID；CARD_REFUND/GATEWAY_REFUND 成功后登记）',
  `ORDER_ID`         bigint       NOT NULL COMMENT '订单ID（冗余自分账行，执行扫描与对账用）',
  `SPLIT_ID`         bigint       NOT NULL COMMENT '分账行ID（ws_split_record.ID）',
  `CLAWBACK_AMOUNT`  bigint       NOT NULL COMMENT '本次应冲金额(分)：按行水费线原始份额比例分摊实际退款额，平台行吃舍入余数',
  `CLAWBACK_STATUS`  tinyint      NOT NULL COMMENT '状态(1387)：1待处理 2已完成 3需人工',
  `PROCESS_REMARK`   varchar(500) COMMENT '处理备注(max500)：失败原因/人工转办说明',
  PRIMARY KEY (`ID`),
  -- 同一动作对同一分账行恒一行：重放与并发登记的库层幂等闸
  UNIQUE KEY `uk_split_clawback_action_split` (`ACTION_ID`, `SPLIT_ID`),
  INDEX `idx_split_clawback_status` (`CLAWBACK_STATUS`),
  INDEX `idx_split_clawback_order` (`ORDER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='分润冲减事实表（D-420 R1 两段式）';

INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (SELECT '分润冲减状态' AS DICT_NAME, '1387' AS DICT_TYPE, '冲减事实处理状态' AS DICT_REMARK) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1387' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '待处理' AS DICT_LABEL
  UNION ALL SELECT '1387', 2, 2, '已完成'
  UNION ALL SELECT '1387', 3, 3, '需人工'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

-- ----------------------------
-- 分润冲减动作级 outbox（D-420 R2）：客户退款成功事务内唯一写入的冲减登记——
-- 单行 INSERT、零分账读、ACTION_ID 唯一；分账形状解析/比例分摊/额度校验/明细
-- 生成全部在独立执行事务完成，任何分账证据异常置 3 需人工，绝不回滚客户退款。
-- Worker 以本表为发现源：分项明细缺失/被改时动作仍可被发现并对账。
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_split_clawback_action` (
  `ID`                 bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`        tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：冲减事实必须保持0',
  `CREATE_BY`          bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`        varchar(14)  NOT NULL COMMENT '创建时间（登记时刻，随退款成功事务）',
  `UPDATE_BY`          bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`        varchar(14)  NOT NULL COMMENT '更新时间',
  `ACTION_ID`          bigint       NOT NULL COMMENT '售后动作ID（ws_after_sale_action.ID）',
  `ORDER_ID`           bigint       NOT NULL COMMENT '订单ID（登记时冻结，执行段与权威动作比对）',
  `ACTION_TYPE`        tinyint      NOT NULL COMMENT '动作类型（登记时冻结）：1卡内退款 3机构退款',
  `REFUND_PRODUCT_FEN` bigint       NOT NULL COMMENT '水品实退金额(分)（登记时冻结，执行段分摊基数）',
  `OUTBOX_STATUS`      tinyint      NOT NULL COMMENT '状态(1387)：1待处理 2已完成 3需人工',
  `PROCESS_REMARK`     varchar(500) COMMENT '处理备注(max500)',
  PRIMARY KEY (`ID`),
  -- 同一售后动作恒一条冲减登记：重放撞键走等价核验，参数漂移转人工
  UNIQUE KEY `uk_split_clawback_action` (`ACTION_ID`),
  INDEX `idx_clawback_action_status` (`OUTBOX_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='分润冲减动作级outbox（D-420 R2）';

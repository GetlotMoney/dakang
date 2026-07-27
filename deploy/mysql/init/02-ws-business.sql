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
  `ONLINE_STATUS`      tinyint      NOT NULL COMMENT '在线状态(1300)：1在线 2离线 3未激活',
  `RUN_STATUS`         tinyint      NOT NULL COMMENT '运行状态(1301)：1空闲 2出水中 3故障 4维护中 5锁机',
  `LAST_HEARTBEAT`     varchar(14)  COMMENT '最后心跳时间',
  `LAST_FAULT_CODE`    varchar(20)  COMMENT '最近故障码(max20)',
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
  `CMD_TYPE`         tinyint      NOT NULL COMMENT '指令类型(1320)：1开始出水 2停止出水 3查询状态 4锁机 5解锁 6参数同步 7重启',
  `CMD_PAYLOAD`      text         COMMENT '下发报文JSON（出水口/水种/计划水量等）',
  `CMD_STATUS`       tinyint      NOT NULL COMMENT '指令状态(1321)：1待下发 2已下发 3已回执 4执行成功 5执行失败 6超时 7部分完成',
  `SENT_TIME`        varchar(14)  COMMENT '下发时间',
  `ACK_TIME`         varchar(14)  COMMENT '设备回执时间',
  `FINISH_TIME`      varchar(14)  COMMENT '终态时间（成功/失败/超时/部分完成）',
  `RESULT_PAYLOAD`   text         COMMENT '执行结果报文JSON（实际水量等，异常补偿依据）',
  `FAIL_REASON`      varchar(500) COMMENT '失败/超时原因(max500)',
  `RETRY_COUNT`      tinyint      NOT NULL DEFAULT 0 COMMENT '重试次数',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：CMD_NO 是设备回执匹配与重复下发防护的根，
  -- 唯一约束防止回执与补传并发到达时重复写入同一指令。
  UNIQUE INDEX `uk_cmd_no` (`CMD_NO`),
  INDEX `idx_cmd_device_status` (`DEVICE_ID`, `CMD_STATUS`),
  INDEX `idx_cmd_order` (`ORDER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='设备指令表';

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
  `PAYMENT_ID`       bigint       NOT NULL COMMENT '支付单ID',
  `REFUND_AMOUNT`    bigint       NOT NULL COMMENT '退款金额(分)，套餐快照单价折算',
  `REFUND_REASON`    varchar(500) NOT NULL COMMENT '退款原因(max500)：出水不足补偿/用户取消/申诉赔付',
  `REFUND_STATUS`    tinyint      NOT NULL COMMENT '退款状态(1343)：1退款中 2退款成功 3退款失败',
  `WX_REFUND_ID`     varchar(64)  COMMENT '微信退款单号(max64)',
  `CALLBACK_TIME`    varchar(14)  COMMENT '退款回调时间',
  `CALLBACK_PAYLOAD` text         COMMENT '退款回调原文JSON',
  PRIMARY KEY (`ID`),
  -- 有意偏离 mysql8 规范“不加唯一索引”：退款单号是资金安全键，重复发起将造成重复退款。
  UNIQUE INDEX `uk_refund_no` (`REFUND_NO`),
  INDEX `idx_refund_order` (`ORDER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='退款单表';

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
  `RECEIVER_TYPE`    tinyint      NOT NULL COMMENT '接收方类型(1)：1机主 2配送员 3平台 4渠道(预留)',
  `RECEIVER_USER_ID` bigint       COMMENT '接收方用户ID（平台时为空）',
  `SPLIT_AMOUNT`     bigint       NOT NULL COMMENT '分账金额(分)',
  `SPLIT_RATE_SNAP`  varchar(20)  NOT NULL COMMENT '分账比例快照(如"70.00")，规则变更不影响历史',
  `SPLIT_STATUS`     tinyint      NOT NULL COMMENT '分账状态(1345)：1待分账 2已分账 3分账失败 4已回退',
  `WX_SPLIT_NO`      varchar(64)  COMMENT '微信分账单号(max64)',
  `SPLIT_TIME`       varchar(14)  COMMENT '分账完成时间',
  `SPLIT_REMARK`     varchar(500) COMMENT '备注(max500)',
  PRIMARY KEY (`ID`),
  INDEX `idx_split_order` (`ORDER_ID`),
  INDEX `idx_split_receiver` (`RECEIVER_TYPE`, `RECEIVER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='分账记录表';

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
  `OWNER_USER_ID`    bigint       NOT NULL COMMENT '登记人用户ID（ws_user.ID）；举证/三照只允许本人登记的媒体，防挪用他人证据',
  `MEDIA_PURPOSE`    tinyint      NOT NULL COMMENT '用途：1签收三照 2申诉举证 3异常举证（跨用途引用一律拒绝）',
  `CONTENT_SHA256`   char(64)     NOT NULL COMMENT '内容SHA-256（完整性校验元数据；本地适配器落盘后校验）',
  `SIZE_BYTES`       bigint       NOT NULL COMMENT '内容字节数',
  `MIME_TYPE`        varchar(50)  NOT NULL COMMENT 'MIME类型(max50)，仅允许 image/*',
  `BOUND_TASK_ID`    bigint       COMMENT '已绑定任务ID；签收/举证提交时原子占用（WHERE BOUND_TASK_ID IS NULL），防同一照片跨任务复用',
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
  `RULE_STATUS`      tinyint      NOT NULL COMMENT '规则状态(1355)：1启用 2停用',
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
  `MSG_CHANNEL`      tinyint      NOT NULL COMMENT '渠道：1站内（一期固定；微信订阅消息不在本期范围）',
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
-- 菜单与权限统一由 03-demo-baseline.sql 建立
-- ----------------------------

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
  -- 回执与补传可能并发到达，必须由数据库层兜底唯一。
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
-- 指令 4 为订单 1（已完成）的取水指令：共键 ORDER_ID=1 且状态/时间与订单闭环对齐（2026-07-20 收口轮）。
(4, 0, 1, '20260710130000', 1, '20260710130500', 'CMD-20260710-0004', 1, 1, 1, '{"outletNo":1,"waterType":"纯净水","planMl":5000,"orderNo":"WO20260710130000001"}', 4, '20260710130000', '20260710130030', '20260710130500', '{"actualMl":5000}', NULL, 0);

-- ----------------------------

-- 菜单与按钮权限统一维护在 deploy/mysql/init/03-demo-baseline.sql；本领域 SQL 只定义表、字典和样例数据。

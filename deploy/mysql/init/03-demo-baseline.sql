-- ============================================================
-- 六维达康 PC Demo 业务基线收口（03-demo-baseline.sql）
-- 在 01 基础底座与 02 业务表之后执行：补水种、追溯字段、5+1 最终菜单、样例用户和超管授权。
-- 本文件描述当前终态，不包含旧项目菜单迁移或历史补丁。
-- ============================================================
SET NAMES utf8mb4;
USE dakang;

-- A. 水种字典表（REQ-073：启停/排序/默认水种/后台维护，不得只在价格表定义水种）
-- ============================================================
DROP TABLE IF EXISTS `ws_water_type`;
CREATE TABLE `ws_water_type` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `WATER_NAME`       varchar(20)  NOT NULL COMMENT '水种名称(max20)，业务唯一，代码层查重',
  `WATER_SORT`       int          NOT NULL COMMENT '排序',
  `DEFAULT_FLAG`     tinyint      NOT NULL COMMENT '是否默认水种(1)：1否 2是（全局仅一个默认，代码层保证）',
  `WATER_STATUS`     tinyint      NOT NULL COMMENT '状态(10)：1正常 2禁用（禁用后新订单/新出水口不可选）',
  `WATER_DESC`       varchar(500) COMMENT '水种说明(max500)',
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='水种字典表';

-- 8 种水占位数据（甲方未给最终定义，REQ-005：MVP 可用占位水种，名称确认后仅改名不改ID）
INSERT IGNORE INTO `ws_water_type` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `WATER_NAME`, `WATER_SORT`, `DEFAULT_FLAG`, `WATER_STATUS`, `WATER_DESC`) VALUES
(1, 0, 1, '20260712120000', 1, '20260712120000', '纯净水',   1, 2, 1, '占位水种，待甲方确认8种水最终定义'),
(2, 0, 1, '20260712120000', 1, '20260712120000', '矿物质水', 2, 1, 1, '占位水种，待甲方确认'),
(3, 0, 1, '20260712120000', 1, '20260712120000', '弱碱水',   3, 1, 1, '占位水种，待甲方确认'),
(4, 0, 1, '20260712120000', 1, '20260712120000', '富氢水',   4, 1, 1, '占位水种，待甲方确认'),
(5, 0, 1, '20260712120000', 1, '20260712120000', '山泉水',   5, 1, 1, '占位水种，待甲方确认'),
(6, 0, 1, '20260712120000', 1, '20260712120000', '苏打水',   6, 1, 1, '占位水种，待甲方确认'),
(7, 0, 1, '20260712120000', 1, '20260712120000', '母婴水',   7, 1, 1, '占位水种，待甲方确认'),
(8, 0, 1, '20260712120000', 1, '20260712120000', '冰川水',   8, 1, 2, '占位水种（默认禁用示例），待甲方确认');

-- ============================================================
-- B. 出水口补水种ID，并按名称回填出水口/配送任务（任务表已在 02 以终态建列）
-- ============================================================
ALTER TABLE `ws_device_outlet` ADD COLUMN `WATER_TYPE_ID` bigint COMMENT '水种ID（ws_water_type.ID）' AFTER `OUTLET_NO`;

UPDATE `ws_device_outlet` o JOIN `ws_water_type` w ON o.`WATER_TYPE` = w.`WATER_NAME` SET o.`WATER_TYPE_ID` = w.`ID` WHERE o.`WATER_TYPE_ID` IS NULL;
UPDATE `ws_delivery_task` d JOIN `ws_water_type` w ON d.`WATER_TYPE` = w.`WATER_NAME` SET d.`WATER_TYPE_ID` = w.`ID` WHERE d.`WATER_TYPE_ID` IS NULL;

-- 签收三照 JSON 结构补充约定（REQ-016：照片带时间戳与GPS，缺一不可）
ALTER TABLE `ws_delivery_task` MODIFY COLUMN `SIGN_PHOTOS` text COMMENT '签收三照JSON数组，元素{type:1门牌/2水品/3摆放, url, lat, lng, time}（OSS URL，时间戳/GPS必填）';

-- ============================================================
-- C. 渠道归属预留字段（REQ-056 骨架验收：数据模型保留归属字段；ws_station 已有 CHANNEL_USER_ID 不动）
-- ============================================================
ALTER TABLE `ws_device`
  ADD COLUMN `CHANNEL_USER_ID` bigint COMMENT '归属渠道用户ID（一期仅归属预留，不做渠道端）' AFTER `OWNER_USER_ID`;

ALTER TABLE `ws_order`
  ADD COLUMN `CHANNEL_USER_ID` bigint COMMENT '下单时归属渠道用户ID快照（一期预留，分润归因依据，随归属变更不回溯）' AFTER `USER_ID`;

-- E2E-08 归因：推荐人快照与渠道分列（邀请人≠渠道，两种归属不得挪用同列）
ALTER TABLE `ws_order`
  ADD COLUMN `REFERRER_USER_ID` bigint COMMENT '下单时推荐人快照（E2E-08 分润归因依据，随归属变更不回溯；绑定前订单恒 NULL）' AFTER `CHANNEL_USER_ID`;

-- ============================================================
-- D. 领域事件身份上下文字典
--    ACTOR_ID / ACTOR_PORTAL / ACTOR_ROLE 已由 02-ws-business.sql 按终态建表，
--    此处只维护字典与菜单数据，避免空库初始化时重复加列。
-- ============================================================

INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT '操作端口', '1364', '领域事件/审计的操作端口来源'
WHERE NOT EXISTS (
  SELECT 1 FROM `api_dict_type` WHERE `DICT_TYPE` = '1364'
);

INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, '1364', seed.dict_sort, seed.dict_value, seed.dict_label
FROM (
  SELECT 1 AS dict_sort, 1 AS dict_value, '公司后台' AS dict_label
  UNION ALL SELECT 2, 2, '用户端'
  UNION ALL SELECT 3, 3, '机主端'
  UNION ALL SELECT 4, 4, '配送端'
  UNION ALL SELECT 5, 5, '渠道端'
  UNION ALL SELECT 6, 6, '系统'
  UNION ALL SELECT 7, 7, '设备'
) seed
LEFT JOIN `api_dict_data` existing
  ON existing.`DICT_TYPE` = '1364' AND existing.`DICT_VALUE` = seed.dict_value
WHERE existing.`ID` IS NULL;

-- ============================================================
-- E. PC Demo 5+1 最终菜单与权限（固定 ID，支持重复初始化）
-- ============================================================
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`) VALUES
(1000,'水站管理',1,'ri:map-pin-2-line',0,92,'/station','/index/index',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1001,'设备中控',1,'ri:cpu-line',0,90,'/device','/index/index',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1002,'水种套餐',1,'ri:drop-line',0,88,'/product','/index/index',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1003,'用户管理',1,'ri:user-3-line',0,86,'/user','/index/index',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1004,'订单中心',1,'ri:file-list-3-line',0,84,'/order','/index/index',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1010,'水站档案',2,NULL,1000,1,'index','/station/index',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1020,'设备档案',2,NULL,1001,4,'index','/device/index',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1021,'指令记录',2,NULL,1001,3,'command','/device/command',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1022,'设备详情',2,NULL,1001,1,'detail','/device/detail',1,NULL,NULL,2,1,0,1,'20260714120000',1,'20260714120000'),
(1023,'设备运营配置',2,NULL,1001,2,'operations','/device/operations',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1030,'水种管理',2,NULL,1002,2,'water','/product/water',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1031,'套餐管理',2,NULL,1002,1,'package','/product/package',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1040,'C端用户',2,NULL,1003,3,'index','/user/index',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1041,'水卡与授权',2,NULL,1003,2,'card','/user/card',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1042,'配送员准入',2,NULL,1003,1,'courier','/user/courier',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
-- E2E-07 消息记录（REQ-087「后台记录」）：全域站内消息只读查询，挂用户管理（触达对象是 C 端用户）
(1027,'消息记录',2,NULL,1003,0,'message','/user/message',1,NULL,NULL,1,1,0,1,'20260731120000',1,'20260731120000'),
(1050,'订单查询',2,NULL,1004,3,'index','/order/index',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1051,'配送履约',2,NULL,1004,2,'delivery','/order/delivery',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1052,'申诉处理',2,NULL,1004,1,'appeal','/order/appeal',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
-- E2E-08 财务面（挂订单中心组，不动 5+1 契约）：分账明细/日对账/比例配置 + 三个高风险功能点
(1028,'分账明细',2,NULL,1004,0,'split','/order/split',1,NULL,NULL,1,1,0,1,'20260731120000',1,'20260731120000'),
(1029,'日对账',2,NULL,1004,0,'reconcile','/order/reconcile',1,NULL,NULL,1,1,0,1,'20260731120000',1,'20260731120000'),
(1032,'分账比例配置',2,NULL,1004,0,'splitconfig','/order/splitconfig',1,NULL,NULL,1,1,0,1,'20260731120000',1,'20260731120000'),
(1145,'对账触发',3,NULL,1029,1,NULL,NULL,1,'finance:reconcile:run','finance:reconcile:run',2,1,0,1,'20260731120000',1,'20260731120000'),
(1146,'比例配置变更',3,NULL,1032,1,NULL,NULL,1,'finance:config:edit','finance:config:edit',2,1,0,1,'20260731120000',1,'20260731120000'),
(1147,'赠卡发放',3,NULL,1041,2,NULL,NULL,1,'user:card:issue','user:card:issue',2,1,0,1,'20260731120000',1,'20260731120000'),
(1148,'提现审核',3,NULL,1028,1,NULL,NULL,1,'finance:withdraw:audit','finance:withdraw:audit',2,1,0,1,'20260731120000',1,'20260731120000'),
(1060,'安全与合规',2,NULL,607,6,'compliance','/system/compliance',1,NULL,NULL,1,1,0,1,'20260714120000',1,'20260714120000'),
(1100,'新增水站',3,NULL,1010,1,NULL,NULL,1,'station:station:add','station:station:add',2,1,0,1,'20260714120000',1,'20260714120000'),
(1101,'修改水站',3,NULL,1010,2,NULL,NULL,1,'station:station:update','station:station:update',2,1,0,1,'20260714120000',1,'20260714120000'),
(1102,'删除水站',3,NULL,1010,3,NULL,NULL,1,'station:station:delete','station:station:delete',2,1,0,1,'20260714120000',1,'20260714120000'),
(1110,'新增设备',3,NULL,1020,1,NULL,NULL,1,'device:device:add','device:device:add',2,1,0,1,'20260714120000',1,'20260714120000'),
(1111,'修改设备',3,NULL,1020,2,NULL,NULL,1,'device:device:update','device:device:update',2,1,0,1,'20260714120000',1,'20260714120000'),
(1112,'删除设备',3,NULL,1020,3,NULL,NULL,1,'device:device:delete','device:device:delete',2,1,0,1,'20260714120000',1,'20260714120000'),
(1113,'下发设备指令',3,NULL,1021,1,NULL,NULL,1,'device:command:send','device:command:send',2,1,0,1,'20260714120000',1,'20260714120000'),
(1120,'新增水种',3,NULL,1030,1,NULL,NULL,1,'product:water:add','product:water:add',2,1,0,1,'20260714120000',1,'20260714120000'),
(1121,'修改水种',3,NULL,1030,2,NULL,NULL,1,'product:water:update','product:water:update',2,1,0,1,'20260714120000',1,'20260714120000'),
(1122,'删除水种',3,NULL,1030,3,NULL,NULL,1,'product:water:delete','product:water:delete',2,1,0,1,'20260714120000',1,'20260714120000'),
(1130,'变更水卡状态',3,NULL,1041,1,NULL,NULL,1,'user:card:status','user:card:status',2,1,0,1,'20260714120000',1,'20260714120000'),
(1131,'新增配送员',3,NULL,1042,1,NULL,NULL,1,'user:courier:add','user:courier:add',2,1,0,1,'20260714120000',1,'20260714120000'),
(1132,'配送员准入审核',3,NULL,1042,2,NULL,NULL,1,'user:courier:audit','user:courier:audit',2,1,0,1,'20260714120000',1,'20260714120000'),
(1133,'申诉裁决',3,NULL,1052,1,NULL,NULL,1,'order:appeal:handle','order:appeal:handle',2,1,0,1,'20260714120000',1,'20260714120000'),
(1134,'设备运营配置维护',3,NULL,1023,1,NULL,NULL,1,'device:operations:config','device:operations:config',2,1,0,1,'20260714120000',1,'20260714120000'),
(1135,'审计导出申请',3,NULL,1060,1,NULL,NULL,1,'system:audit:export','system:audit:export',2,1,0,1,'20260714120000',1,'20260714120000'),
(1136,'新增套餐',3,NULL,1031,1,NULL,NULL,1,'product:package:add','product:package:add',2,1,0,1,'20260719120000',1,'20260719120000'),
(1137,'修改套餐',3,NULL,1031,2,NULL,NULL,1,'product:package:update','product:package:update',2,1,0,1,'20260719120000',1,'20260719120000'),
(1138,'套餐上下架',3,NULL,1031,3,NULL,NULL,1,'product:package:shelf','product:package:shelf',2,1,0,1,'20260719120000',1,'20260719120000'),
-- E2E-04 包A 售后功能点。必须写在本 VALUES 列表里（而不是文件更下方）：
-- 下面「超级管理员绑定全部启用菜单」那段是 SELECT ... FROM api_rbac_menu，
-- 只能绑定到那一刻已存在的行，写在它之后的菜单永远拿不到角色绑定 —— 表现是
-- 菜单一切正常、点执行恒 403。同一份权限在 migrations/2026-07-29-aftersale-e2e04-a.sql
-- 里另有一份（供已有库升级），两处的 ID 与权限码必须逐字一致。
-- 暂挂 1052 申诉处理 之下：包A 尚无独立售后页面，入口在申诉处理与订单详情两处；
-- 前端售后台账落地后只改 MENU_PARENT_ID，ID 与权限码不变。
(1139,'售后台账查询',3,NULL,1052,2,NULL,NULL,1,'order:aftersale:query','order:aftersale:query',2,1,0,1,'20260729120000',1,'20260729120000'),
(1140,'售后执行与核账',3,NULL,1052,3,NULL,NULL,1,'order:aftersale:handle','order:aftersale:handle',2,1,0,1,'20260729120000',1,'20260729120000'),
-- 包D-5：充值/购卡退款走独立财务审核权限（R0-7）。与配送售后的日常处理权分开——
-- 「把钱退回用户支付账户」与「处理一条申诉」不是一个风险级别，共用开关等于默认全员可退款
(1141,'发起充值退款',3,NULL,1052,4,NULL,NULL,1,'order:aftersale:refund','order:aftersale:refund',2,1,0,1,'20260729120000',1,'20260729120000'),
-- E2E-05 设备运营与运维：三个二级子页面 + 三个功能点。仍在本 VALUES 列表内（超管绑定 SELECT 之前）。
-- 二级菜单挂 1001 设备中控；sort 依既有排序语义（值小在下）。
(1024,'告警中心',2,NULL,1001,6,'alarm','/device/alarm',1,NULL,NULL,1,1,0,1,'20260730120000',1,'20260730120000'),
(1025,'运维工单',2,NULL,1001,5,'workorder','/device/workorder',1,NULL,NULL,1,1,0,1,'20260730120000',1,'20260730120000'),
(1026,'批量控制',2,NULL,1001,0,'batch','/device/batch',1,NULL,NULL,1,1,0,1,'20260730120000',1,'20260730120000'),
-- 告警处置（确认/忽略/转工单）与工单处理（确认/驳回/分配/复核）是设备侧日常运维权；
-- 批量控制单列权限：锁全站/全网设备的破坏半径远大于单设备指令，不与 device:command:send 共用开关
(1142,'告警处置',3,NULL,1024,1,NULL,NULL,1,'device:alarm:handle','device:alarm:handle',2,1,0,1,'20260730120000',1,'20260730120000'),
(1143,'工单处理',3,NULL,1025,1,NULL,NULL,1,'device:workorder:handle','device:workorder:handle',2,1,0,1,'20260730120000',1,'20260730120000'),
(1144,'批量指令执行',3,NULL,1026,1,NULL,NULL,1,'device:batch:execute','device:batch:execute',2,1,0,1,'20260730120000',1,'20260730120000');

-- C 端用户样例仅用于 Demo 查询和跨表追溯；身份证字段保存不可逆占位密文，不放真实身份信息。
INSERT IGNORE INTO `ws_user`
(`ID`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`USER_NAME`,`USER_GENDER`,`USER_PHONE`,`USER_IDENTITY_CIPHER`,`USER_AVATAR`,`DISABLED_FLAG`,`USER_STATUS`,`POINTS`,`WECHAT_XCX_OPENID`,`CHANNEL_USER_ID`,`REFERRER_USER_ID`,`PROMO_CODE`) VALUES
(1,1,'20260710120000',1,'20260710120000',0,'张女士',1,'13900001111','demo-cipher-0001',NULL,1,1,0,NULL,NULL,NULL,NULL),
(2,1,'20260710120000',1,'20260710120000',0,'李配送',1,'13800001111','demo-cipher-0002',NULL,1,1,0,NULL,NULL,NULL,NULL),
(3,1,'20260710120000',1,'20260710120000',0,'王待审',2,'13800002222','demo-cipher-0003',NULL,1,1,0,NULL,NULL,NULL,NULL),
(4,1,'20260710120000',1,'20260710120000',0,'赵先生',1,'13700003333','demo-cipher-0004',NULL,1,1,0,NULL,NULL,NULL,NULL),
(5,1,'20260710120000',1,'20260710120000',0,'钱女士',2,'13600004444','demo-cipher-0005',NULL,1,1,0,NULL,NULL,NULL,NULL);

-- 超级管理员绑定全部启用菜单；NOT EXISTS 保证重复执行不会重复授权。
UPDATE `api_rbac_role_menu` SET `DATA_STATUS` = 0 WHERE `ROLE_ID` = 1 AND `DATA_STATUS` <> 0;
INSERT IGNORE INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260714120000',1,'20260714120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`DATA_STATUS` = 0
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

-- ============================================================
-- 【仅限空库】本文件以 DROP TABLE IF EXISTS 开头，只能用于全新空库。
-- 对既有库（主库 3308／验收库 3309／任何已部署环境）执行会静默删光本域全部数据，
-- 无任何提示、不可恢复。既有库一律改走 deploy/mysql/migrations/ 下的非破坏迁移。
-- 详见 AGENTS.md「第 1 步：数据库设计」第 5 条。
-- ============================================================
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

-- 菜单与按钮权限统一维护在 deploy/mysql/init/03-demo-baseline.sql；本领域 SQL 只定义表、字典和样例数据。

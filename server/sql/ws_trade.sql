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
  `PAY_SOURCE`       tinyint      NOT NULL COMMENT '支付来源：1微信 2Pay-Sim；由服务端支付适配器创建时写入，创建后不可改（L2-DB 契约§5.2）',
  `CURRENCY`         varchar(16)  NOT NULL DEFAULT 'CNY' COMMENT '币种，一期固定CNY（L2-DB 契约§5.2）',
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

-- 菜单与按钮权限统一维护在 deploy/mysql/init/03-demo-baseline.sql；本领域 SQL 只定义表、字典和样例数据。

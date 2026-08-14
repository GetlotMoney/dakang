-- ============================================================
-- 【仅限空库】本文件以 DROP TABLE IF EXISTS 开头，只能用于全新空库。
-- 对既有库（主库 3308／验收库 3309／任何已部署环境）执行会静默删光本域全部数据，
-- 无任何提示、不可恢复。既有库一律改走 deploy/mysql/migrations/ 下的非破坏迁移。
-- 详见 AGENTS.md「第 1 步：数据库设计」第 5 条。
-- ============================================================
-- ============================================================
-- 六维达康 · 交易域（ws_trade）
-- 表：ws_order / ws_payment / ws_refund / ws_wallet_flow / ws_split_record / ws_split_plan / ws_split_plan_item / ws_split_component / ws_region_agent / ws_owner_referrer / ws_owner_attribution
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

-- 分账比例配置的商品线锁锚表（R1 P1-3；与 init/migration 三轨逐字一致）
DROP TABLE IF EXISTS `ws_split_line_lock`;
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
DROP TABLE IF EXISTS `ws_split_plan`;
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

DROP TABLE IF EXISTS `ws_split_plan_item`;
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

DROP TABLE IF EXISTS `ws_split_component`;
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

-- ----------------------------
-- 区域服务商归属（版本化）：回答「这个区县/市/省的运营中心是谁」
--
-- 分润 V2 的计划项早就能表达 REGION_PROVINCE/CITY/COUNTY 三级比例，但在本表出现前，
-- 全库没有任何地方存得下「谁是这个区的服务商」——唯一沾边的 ws_station.STATION_REGION
-- 是自由文本，按它匹配等于按字符串猜行政区划。
--
-- 【本表不决定订单归属】D-406：归属按推荐血缘冻结、不按地缘重算，计算器不收行政区字段。
-- 本表只服务 D-407 的人工分配台账与运营筛选。
-- 版本化而非一行改到底：领地会换人，审计要能回答"三月份这个区归谁"，UPDATE 会抹掉这段历史。
-- AGENT_STATUS=2 的版本行表示「自该时点起该区域无服务商」——空态必须可表达，
-- 靠删行会让那段时间退回更早版本，等于把已解约的人又接回去。
--
-- 本表不含任何比例。比例在 ws_split_plan_item，待甲方书面确认。
-- ----------------------------
DROP TABLE IF EXISTS `ws_region_agent`;
CREATE TABLE `ws_region_agent` (
  `ID`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`   tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`     bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`   varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`     bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`   varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `REGION_LEVEL`  varchar(16)  NOT NULL COMMENT '区域层级：PROVINCE/CITY/COUNTY，与 SplitV2Enum.RegionLevel 同源（COUNTY 对应 ws_station.DISTRICT_CODE）',
  `REGION_CODE`   varchar(6)   NOT NULL COMMENT '行政区划码(GB/T 2260)，与 ws_station 上同层级的码等值匹配',
  `REGION_NAME`   varchar(50)  NOT NULL COMMENT '区域名称，仅供排障与后台展示，绝不参与匹配',
  `AGENT_USER_ID` bigint       NOT NULL COMMENT '服务商用户ID(ws_user.ID)；本表是领地登记，不决定订单归属（D-406 归属按血缘冻结）',
  `AGENT_STATUS`  tinyint      NOT NULL COMMENT '状态：1生效 2停用；停用行表示自 EFFECT_TIME 起该区域无服务商',
  `EFFECT_TIME`   varchar(14)  NOT NULL COMMENT '生效时间（含）yyyyMMddHHmmss；按订单创建时点选版本，变更不追溯',
  `AGENT_REMARK`  varchar(200) DEFAULT NULL COMMENT '备注(max200)：换签原因、合同号等',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_region_agent_version` (`REGION_LEVEL`, `REGION_CODE`, `EFFECT_TIME`),
  KEY `idx_region_agent_lookup` (`REGION_LEVEL`, `REGION_CODE`, `EFFECT_TIME`),
  KEY `idx_region_agent_user` (`AGENT_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='区域服务商归属（版本化）：区域×生效时间→服务商，不含任何分润比例';

-- 不插任何归属种子：谁是哪个区的服务商属于运营事实，必须由后台录入并留痕，
-- 不能由建表脚本替甲方决定。跨区县级差等场景由真库测试自行构造数据。

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

-- ----------------------------
-- 分润冲减事实表（D-420 R1）：每(售后动作,分账行)一行冲减事实，两段式——
-- 登记与客户退款成功同事务（outbox 语义：退款成功即事实存在，绝不因冲减失败回滚）；
-- 执行独立事务可重试可转人工。多次部分退款=多个 ACTION 各自成行、同行累计；
-- uk(ACTION_ID,SPLIT_ID) 保证同一动作对同一分账行恒零重复。
-- ----------------------------
DROP TABLE IF EXISTS `ws_split_clawback`;
CREATE TABLE `ws_split_clawback` (
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
DROP TABLE IF EXISTS `ws_split_clawback_action`;
CREATE TABLE `ws_split_clawback_action` (
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

-- ----------------------------
-- 机主加盟推荐关系（D-404/D-428）：谁把这个机主招进来的。
-- 与用户邀请链（ws_user.REFERRER_USER_ID）物理分列——那条链回答「喝水的人谁拉来的」，
-- 本表回答「这台机器的机主谁招来的」，D-404 明令不得混用；一人一行、建立即冻结（D-406），
-- 无更新/删除入口，录错走申诉流程（REQ-058，未开放）。
-- ----------------------------
DROP TABLE IF EXISTS `ws_owner_referrer`;
CREATE TABLE `ws_owner_referrer` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `OWNER_USER_ID`    bigint       NOT NULL COMMENT '机主用户ID(ws_user.ID)；一人一行终身冻结',
  `REFERRER_USER_ID` bigint       NOT NULL COMMENT '直接推荐人用户ID(ws_user.ID)；任何身份可担任，仅此一级(D-404)',
  `BIND_SOURCE`      varchar(20)  NOT NULL COMMENT '建立来源：ADMIN_ENTRY后台录入/INVITE_LINK邀请链路(预留)',
  `BIND_TIME`        varchar(14)  NOT NULL COMMENT '建立时点；建立即冻结(D-406)，换绑走申诉流程(REQ-058未开放)',
  `REFERRER_REMARK`  varchar(200) DEFAULT NULL COMMENT '备注(max200)',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_owner_referrer_owner` (`OWNER_USER_ID`),
  KEY `idx_owner_referrer_ref` (`REFERRER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='机主加盟推荐关系：谁招来的这个机主；与用户邀请链物理分列(D-404)，一人一行建立即冻结';

-- ----------------------------
-- 机主区域归属链（D-406/D-407/D-428）：这个机主的省/市/区县运营中心各是谁。
-- 血缘冻结不按地缘：跨行政区放设备不改归属；无行=公域未分配（推荐/区域份额落平台）。
-- 三级可任意留空（缺席层级差切片归最近在场上级，全空才归公司，D-428）；
-- 三级在场者必须互不相同（服务层校验：同人兼两级会破坏分账行聚合与冲减份额重算）。
-- ----------------------------
DROP TABLE IF EXISTS `ws_owner_attribution`;
CREATE TABLE `ws_owner_attribution` (
  `ID`                     bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`            tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`              bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`            varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`              bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`            varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `OWNER_USER_ID`          bigint       NOT NULL COMMENT '机主用户ID(ws_user.ID)；一人一行建立即冻结',
  `ATTRIBUTION_SOURCE`     varchar(20)  NOT NULL COMMENT '归属来源：PRIVATE_REFERRAL血缘/PUBLIC_MANUAL公域人工(D-407)；无行=公域未分配',
  `PROVINCE_AGENT_USER_ID` bigint       DEFAULT NULL COMMENT '省级运营中心用户ID；空=该级无人(缺席级差切片归最近在场上级，D-428)',
  `CITY_AGENT_USER_ID`     bigint       DEFAULT NULL COMMENT '市级运营中心用户ID；空=该级无人',
  `COUNTY_AGENT_USER_ID`   bigint       DEFAULT NULL COMMENT '区县级运营中心用户ID；空=该级无人',
  `BIND_TIME`              varchar(14)  NOT NULL COMMENT '建立时点；建立即冻结(D-406)，跨区放机不改归属',
  `ATTRIBUTION_REMARK`     varchar(200) DEFAULT NULL COMMENT '备注(max200)：分配依据、合同号等',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_owner_attribution_owner` (`OWNER_USER_ID`),
  KEY `idx_owner_attr_province` (`PROVINCE_AGENT_USER_ID`),
  KEY `idx_owner_attr_city` (`CITY_AGENT_USER_ID`),
  KEY `idx_owner_attr_county` (`COUNTY_AGENT_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='机主区域归属链：省市区县运营中心各是谁；血缘冻结不按地缘(D-406)，无行=公域未分配';

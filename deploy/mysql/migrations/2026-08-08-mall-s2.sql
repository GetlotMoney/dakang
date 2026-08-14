-- ============================================================
-- E2E-09 商城 S2：购物车、订单、库存预占与 Pay-Sim 支付（既有库增量迁移）
--
-- 依赖：本库已执行 2026-08-08-mall-s1.sql（商城六表在位）。
-- 与 server/sql/ws_mall.sql（领域源，仅限空库）和 deploy/mysql/init/02-ws-business.sql
-- （空库初始化）三轨逐字一致；测试轨见 MallDbSchema.java。
--
-- 幂等：建表用 CREATE TABLE IF NOT EXISTS；加列用 information_schema 探测 + PREPARE；
-- 字典用 NOT EXISTS（api_dict_type/api_dict_data 除主键外无唯一键，IGNORE 无键可撞会翻倍）；
-- 菜单与角色绑定携显式 ID 用 INSERT IGNORE。全文无 DROP/TRUNCATE/无 WHERE 的 DELETE 或 UPDATE。
--
-- 分域理由：商城订单 ID 与一期 ws_order.ID 存在碰撞可能，复用 ws_payment.ORDER_ID
-- 会让两域订单混指同一行——故商城支付单与支付事实独立建表、独立唯一键。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1) 购物车行：一人一 SKU 恒一行；唯一键不含 DATA_STATUS，移除后再加购复活同一行
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_cart_item` (
  `ID`          bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS` tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0在购物车 1已移除；再次加购复活本行',
  `CREATE_BY`   bigint      NOT NULL COMMENT '创建人ID（=归属用户）',
  `CREATE_TIME` varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`   bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME` varchar(14) NOT NULL COMMENT '更新时间',
  `USER_ID`     bigint      NOT NULL COMMENT '归属用户（ws_user.ID）；读写恒以会话人过滤，不收前端 userId',
  `SKU_ID`      bigint      NOT NULL COMMENT 'SKU ID（ws_mall_sku.ID）',
  `QUANTITY`    int         NOT NULL COMMENT '数量(件)：1~999，上限由 CHECK 兜住（连续加购累加同样不得越界）；置0等价移除，由服务层改走逻辑删除',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_cart_user_sku` (`USER_ID`, `SKU_ID`),
  INDEX `idx_mall_cart_user` (`USER_ID`),
  CONSTRAINT `chk_mall_cart_qty_positive` CHECK (`QUANTITY` > 0 AND `QUANTITY` <= 999)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城购物车行表（E2E-09 S2）';

-- ----------------------------
-- 2) 商城订单：一单一仓；收货信息为下单快照；金额恒等式由库层 CHECK 兜底
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_order` (
  `ID`                     bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`            tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：订单恒0，不做业务逻辑删除',
  `CREATE_BY`              bigint       NOT NULL COMMENT '创建人ID（=下单用户）',
  `CREATE_TIME`            varchar(14)  NOT NULL COMMENT '创建时间（下单时刻）',
  `UPDATE_BY`              bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`            varchar(14)  NOT NULL COMMENT '更新时间',
  `ORDER_NO`               varchar(32)  NOT NULL COMMENT '商城订单号：MO+sha256(userId:requestId)前30位，确定性派生、禁日期禁序列',
  `USER_ID`                bigint       NOT NULL COMMENT '下单用户（ws_user.ID）；一切读写按会话人强制过滤',
  `WAREHOUSE_ID`           bigint       NOT NULL COMMENT '履约前置仓（创单选定并冻结）：一单一仓，不拆仓不拆单',
  `REQUEST_ID`             varchar(36)  NOT NULL COMMENT '创单请求号：规范小写 UUID；与 USER_ID 组唯一键=创单幂等锚',
  `ADDRESS_ID`             bigint       NOT NULL COMMENT '来源地址簿行ID：仅用于重放参数等价判定，展示一律取本表快照',
  `PRODUCT_AMOUNT_FEN`     bigint       NOT NULL COMMENT '商品金额(分)：服务端按 SKU 现价重算，禁用前端金额',
  `DELIVERY_FEE_FEN`       bigint       NOT NULL DEFAULT 0 COMMENT '配送费(分)：内部闭环期固定0并落快照；非正式商业运费规则',
  `ORDER_AMOUNT_FEN`       bigint       NOT NULL COMMENT '订单总额(分)：恒等于商品金额+配送费，库层 CHECK 兜底',
  `ORDER_STATUS`           tinyint      NOT NULL COMMENT '订单状态(1393)：1待支付 2已支付待履约 3履约中 4已完成 5已取消/支付关闭 6已全额退款',
  `PAY_EXPIRE_TIME`        varchar(14)  NOT NULL COMMENT '支付截止时间：创单冻结，创建后不可改；到期本身不构成关闭依据（须 Pay-Sim 权威 CLOSED）',
  `RECEIVER_NAME`          varchar(30)  NOT NULL COMMENT '收货人姓名快照(max30)',
  `RECEIVER_PHONE`         varchar(11)  NOT NULL COMMENT '收货电话快照（脱敏前原号）：出参一律经 PhoneMask，不回流前端',
  `RECEIVER_REGION`        varchar(100) NOT NULL COMMENT '省市区文本快照(max100)',
  `RECEIVER_ADDRESS`       varchar(200) NOT NULL COMMENT '详细地址快照(max200)',
  `RECEIVER_DISTRICT_CODE` varchar(6)   NOT NULL COMMENT '收货区县行政区码快照(6位)：选仓判据，用户明确选择而非文本猜测',
  `CANCEL_TIME`            varchar(14)  NULL DEFAULT NULL COMMENT '取消/支付关闭时间',
  `CANCEL_REASON`          varchar(200) NULL DEFAULT NULL COMMENT '取消原因(max200)：用户取消/超时关闭各自写明',
  `VERSION`                int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本：每次状态迁移+1，CAS 校验影响行数',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_order_no` (`ORDER_NO`),
  UNIQUE KEY `uk_mall_order_user_request` (`USER_ID`, `REQUEST_ID`),
  INDEX `idx_mall_order_user_status` (`USER_ID`, `ORDER_STATUS`),
  INDEX `idx_mall_order_status_expire` (`ORDER_STATUS`, `PAY_EXPIRE_TIME`),
  INDEX `idx_mall_order_warehouse` (`WAREHOUSE_ID`),
  CONSTRAINT `chk_mall_order_amount_nonneg` CHECK (`PRODUCT_AMOUNT_FEN` >= 0 AND `DELIVERY_FEE_FEN` >= 0),
  CONSTRAINT `chk_mall_order_amount_sum` CHECK (`ORDER_AMOUNT_FEN` = `PRODUCT_AMOUNT_FEN` + `DELIVERY_FEE_FEN`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城订单表（E2E-09 S2）';

-- ----------------------------
-- 3) 订单明细：商品/SKU/规格/单价/重量下单即冻结；行金额恒等式库层兜底
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_order_item` (
  `ID`              bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`     tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：明细恒0，不做业务逻辑删除',
  `CREATE_BY`       bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`     varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`       bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`     varchar(14)  NOT NULL COMMENT '更新时间',
  `ORDER_ID`        bigint       NOT NULL COMMENT '所属订单（ws_mall_order.ID）',
  `PRODUCT_ID`      bigint       NOT NULL COMMENT '商品ID（ws_mall_product.ID）',
  `SKU_ID`          bigint       NOT NULL COMMENT 'SKU ID（ws_mall_sku.ID）',
  `PRODUCT_NAME`    varchar(100) NOT NULL COMMENT '商品名称快照(max100)',
  `SKU_NAME`        varchar(100) NOT NULL COMMENT 'SKU名称快照(max100)',
  `SPEC_SNAP`       varchar(500) NOT NULL COMMENT '规格快照 JSON(max500)：扁平 string→string，编解码同 MallSpecSnapshot 约束',
  `UNIT_PRICE_FEN`  bigint       NOT NULL COMMENT '成交单价(分)：下单时 SKU 售价快照',
  `QUANTITY`        int          NOT NULL COMMENT '购买数量(件)：1~999，与购物车同界',
  `ITEM_AMOUNT_FEN` bigint       NOT NULL COMMENT '行金额(分)：恒等于单价×数量，库层 CHECK 兜底',
  `WEIGHT_GRAM`     bigint       NOT NULL COMMENT '单件重量(克)快照：履约装载参考',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_order_item_order_sku` (`ORDER_ID`, `SKU_ID`),
  INDEX `idx_mall_order_item_order` (`ORDER_ID`),
  INDEX `idx_mall_order_item_sku` (`SKU_ID`),
  CONSTRAINT `chk_mall_order_item_qty_positive` CHECK (`QUANTITY` > 0 AND `QUANTITY` <= 999),
  CONSTRAINT `chk_mall_order_item_price_nonneg` CHECK (`UNIT_PRICE_FEN` >= 0),
  CONSTRAINT `chk_mall_order_item_amount` CHECK (`ITEM_AMOUNT_FEN` = `UNIT_PRICE_FEN` * `QUANTITY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城订单明细表（E2E-09 S2）';

-- ----------------------------
-- 4) 商城支付单：三条唯一键=一单一支付单 + 交易号不重复
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_payment` (
  `ID`               bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：支付单恒0，不做业务逻辑删除',
  `CREATE_BY`        bigint      NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14) NOT NULL COMMENT '更新时间',
  `ORDER_ID`         bigint      NOT NULL COMMENT '商城订单ID（ws_mall_order.ID；禁与 ws_payment.ORDER_ID 混用）',
  `ORDER_NO`         varchar(32) NOT NULL COMMENT '商城订单号(out_trade_no)',
  `TRANSACTION_ID`   varchar(64) NULL DEFAULT NULL COMMENT '支付方交易号(max64)：Pay-Sim 用不重叠命名空间；NULL 不参与唯一约束',
  `PAY_AMOUNT_FEN`   bigint      NOT NULL COMMENT '应付金额(分)：创单冻结，恒等于订单总额',
  `PAY_STATUS`       tinyint     NOT NULL COMMENT '支付状态(1394)：1待支付 2支付成功 3支付失败 4已关闭',
  `PAY_SOURCE`       tinyint     NOT NULL COMMENT '支付来源：1微信 2Pay-Sim；服务端适配器创建时写入，创建后不可改',
  `CURRENCY`         varchar(16) NOT NULL DEFAULT 'CNY' COMMENT '币种，内部闭环期固定CNY',
  `PAY_EXPIRE_TIME`  varchar(14) NOT NULL COMMENT '支付截止时间：与订单同源冻结，创建后不可改',
  `PAY_SUCCESS_TIME` varchar(14) NULL DEFAULT NULL COMMENT '权威支付成功时间(Asia/Shanghai)：取自支付事实，不取本地时钟',
  `CLOSE_TIME`       varchar(14) NULL DEFAULT NULL COMMENT '支付关闭时间',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_payment_transaction` (`TRANSACTION_ID`),
  UNIQUE KEY `uk_mall_payment_order_no` (`ORDER_NO`),
  UNIQUE KEY `uk_mall_payment_order_id` (`ORDER_ID`),
  CONSTRAINT `chk_mall_payment_amount_nonneg` CHECK (`PAY_AMOUNT_FEN` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城支付单表（E2E-09 S2）';

-- ----------------------------
-- 5) 商城支付事实收件箱：外部事实只落此表，资金推进另起事务（两段式）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_payment_fact` (
  `ID`                 bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`        tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：支付事实必须保持0，不做业务逻辑删除',
  `CREATE_BY`          bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`        varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`          bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`        varchar(14)  NOT NULL COMMENT '更新时间',
  `PAY_SOURCE`         tinyint      NOT NULL COMMENT '支付来源：1微信 2Pay-Sim（服务端适配器常量，不取自报文）',
  `FACT_CHANNEL`       tinyint      NOT NULL COMMENT '事实渠道：1通知 2查询 3Pay-Sim',
  `PROVIDER_EVENT_KEY` varchar(100) NOT NULL COMMENT '外部事实键：Pay-Sim=MALLSIM-<orderNo>，查询=MALLQ:<sha256规范串>；命名空间不得复用',
  `PAYMENT_ID`         bigint       NULL DEFAULT NULL COMMENT '共键关联后的商城支付单ID；未知/错位时为空',
  `ORDER_ID`           bigint       NULL DEFAULT NULL COMMENT '共键关联后的商城订单ID；未知/错位时为空',
  `ORDER_NO`           varchar(32)  NOT NULL COMMENT '外部返回的商户订单号',
  `TRADE_STATE`        varchar(32)  NOT NULL COMMENT '规范化事实状态：SUCCESS/NOTPAY/CLOSED；其他仅留证转人工',
  `TRANSACTION_ID`     varchar(64)  NULL DEFAULT NULL COMMENT '支付方交易号；SUCCESS必填',
  `PAY_AMOUNT_FEN`     bigint       NULL DEFAULT NULL COMMENT '支付方返回金额(分)；SUCCESS必填，未返回必须为空，禁止用内部金额补造',
  `CURRENCY`           varchar(16)  NULL DEFAULT NULL COMMENT '支付方返回币种；SUCCESS必填且为CNY',
  `PAY_SUCCESS_TIME`   varchar(14)  NULL DEFAULT NULL COMMENT '支付成功时间(Asia/Shanghai)；SUCCESS必填',
  `RAW_BODY`           mediumtext   NULL COMMENT '原始事实正文存档（对账依据）',
  `RAW_BODY_SHA256`    char(64)     NOT NULL COMMENT '正文完整性摘要（非不可抵赖证明）',
  `VERIFY_METHOD`      tinyint      NOT NULL COMMENT '校验方式：1微信签名 2微信查询 3Pay-Sim内部',
  `PROCESSING_STATUS`  tinyint      NOT NULL COMMENT '处理状态(1395)：1待处理 2处理中 3已处理 4待重试 5需对账',
  `RETRY_COUNT`        int          NOT NULL DEFAULT 0 COMMENT '重试次数',
  `NEXT_RETRY_TIME`    varchar(14)  NULL DEFAULT NULL COMMENT '下次可 claim 时间',
  `CLAIM_TIME`         varchar(14)  NULL DEFAULT NULL COMMENT 'Worker claim 时间',
  `LEASE_UNTIL`        varchar(14)  NULL DEFAULT NULL COMMENT 'claim 租约到期时间（进程崩溃后可被重新捞取）',
  `LAST_ERROR`         varchar(500) NULL DEFAULT NULL COMMENT '最近一次结构化失败原因(max500)：不写密钥与敏感正文',
  `RECEIVED_TIME`      varchar(14)  NOT NULL COMMENT '服务端接收时间',
  `PROCESSED_TIME`     varchar(14)  NULL DEFAULT NULL COMMENT '完成业务处理的时间',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_payment_fact_key` (`PAY_SOURCE`, `FACT_CHANNEL`, `PROVIDER_EVENT_KEY`),
  INDEX `idx_mall_payment_fact_claim` (`PROCESSING_STATUS`, `NEXT_RETRY_TIME`),
  INDEX `idx_mall_payment_fact_order_no` (`ORDER_NO`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城支付事实收件箱表（E2E-09 S2）';

-- ----------------------------
-- 6) ws_user_address 增列 DISTRICT_CODE（可空）
--    可空是刻意的：存量地址没有区县码，禁止按 REGION 文本猜测回填。
--    未选区县的地址不可用于商城下单，由服务端在结算预览 fail-closed 提示用户补选。
-- ----------------------------
SET @has_district := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'ws_user_address' AND column_name = 'DISTRICT_CODE');
SET @sql := IF(@has_district > 0, 'SELECT 1',
  'ALTER TABLE `ws_user_address` ADD COLUMN `DISTRICT_CODE` varchar(6) NULL DEFAULT NULL
     COMMENT ''收货区县行政区码(6位)：用户明确选择，禁止按 REGION 文本猜测；为空的历史地址不可用于商城下单''
     AFTER `REGION`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ----------------------------
-- 7) 字典：1393~1395 新增；1391 增加 5~8（订单流转类型）
--    必须 NOT EXISTS：两表除主键外无唯一键，IGNORE 无键可撞会翻倍并让字典接口 500
-- ----------------------------
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (
            SELECT '商城订单状态' AS DICT_NAME, '1393' AS DICT_TYPE, '商城订单状态机' AS DICT_REMARK
  UNION ALL SELECT '商城支付状态', '1394', '商城支付单状态'
  UNION ALL SELECT '商城支付事实处理状态', '1395', '商城支付事实收件箱处理状态'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1391' AS DICT_TYPE, 5 AS DICT_SORT, 5 AS DICT_VALUE, '下单预占' AS DICT_LABEL
  UNION ALL SELECT '1391', 6, 6, '预占释放'
  UNION ALL SELECT '1391', 7, 7, '支付实销'
  UNION ALL SELECT '1391', 8, 8, '退货回库'
  UNION ALL SELECT '1393', 1, 1, '待支付'
  UNION ALL SELECT '1393', 2, 2, '已支付待履约'
  UNION ALL SELECT '1393', 3, 3, '履约中'
  UNION ALL SELECT '1393', 4, 4, '已完成'
  UNION ALL SELECT '1393', 5, 5, '已取消'
  UNION ALL SELECT '1393', 6, 6, '已全额退款'
  UNION ALL SELECT '1394', 1, 1, '待支付'
  UNION ALL SELECT '1394', 2, 2, '支付成功'
  UNION ALL SELECT '1394', 3, 3, '支付失败'
  UNION ALL SELECT '1394', 4, 4, '已关闭'
  UNION ALL SELECT '1395', 1, 1, '待处理'
  UNION ALL SELECT '1395', 2, 2, '处理中'
  UNION ALL SELECT '1395', 3, 3, '已处理'
  UNION ALL SELECT '1395', 4, 4, '待重试'
  UNION ALL SELECT '1395', 5, 5, '需对账'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

-- ----------------------------
-- 8) 菜单：商城订单（只读台账，无写权限点）
--    ID/路径/组件与 deploy/mysql/init/03-demo-baseline.sql 逐字一致
-- ----------------------------
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`)
VALUES
(1043,'商城订单',2,NULL,1005,5,'order','/mall/order',1,NULL,NULL,1,1,0,1,'20260808120000',1,'20260808120000');

INSERT IGNORE INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260808120000',1,'20260808120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` IN (1043)
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

-- ----------------------------
-- 后置不变式：五表存在、地址列已加、字典与菜单就位
-- 交易表刻意不带演示种子——没有配套预占流水与 RESERVED_QTY 的假订单会让
-- 库存与订单互相打架，演示数据反而在说谎；订单数据由隔离环境实际下单产生。
-- ----------------------------
SELECT
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
    AND table_name IN ('ws_mall_cart_item','ws_mall_order','ws_mall_order_item',
                       'ws_mall_payment','ws_mall_payment_fact')) AS s2_tables_expect_5,
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'ws_user_address' AND column_name = 'DISTRICT_CODE') AS addr_district_expect_1,
  (SELECT COUNT(*) FROM api_dict_type WHERE DICT_TYPE IN ('1393','1394','1395')) AS dict_types_expect_3,
  (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE = '1391') AS flow_type_values_expect_8,
  (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE IN ('1393','1394','1395')) AS dict_values_expect_15,
  (SELECT COUNT(*) FROM api_rbac_menu WHERE MENU_COMPONENT = '/mall/order') AS menu_expect_1;

SELECT '2026-08-08 迁移完成：E2E-09 S2 交易五表、地址区县码、字典 1393~1395 与商城订单菜单就绪' AS RESULT;

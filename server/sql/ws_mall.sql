-- ============================================================
-- 【仅限空库】商城域领域源文件（E2E-09 S1，2026-08-08）
--
-- 本文件以 DROP TABLE IF EXISTS 开头：对既有库执行会静默删光商城域数据，
-- 不可恢复。变更现有库一律走 deploy/mysql/migrations/2026-08-08-mall-s1.sql。
--
-- 契约：docs/contracts/E2E-09-mall-contract.md（SPU+SKU、仓×SKU 库存、
-- 只增流水；金额恒正整数分、库存恒非负整数件）。
-- 需求锚：REQ-201/203/205；REQ-202/204/206 属 S2~S5。
-- 最终菜单不在本文件（统一 deploy/mysql/init/03-demo-baseline.sql）。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 商品分类
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_category`;
CREATE TABLE `ws_mall_category` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `CATEGORY_CODE`    varchar(50)  NOT NULL COMMENT '分类业务编码(max50)：唯一，逻辑删除后不复用',
  `CATEGORY_NAME`    varchar(50)  NOT NULL COMMENT '分类名称(max50)',
  `CATEGORY_SORT`    int          NOT NULL DEFAULT 0 COMMENT '排序号：小在前',
  `CATEGORY_STATUS`  tinyint      NOT NULL COMMENT '分类状态(1392)：1启用 2停用；停用分类下商品不得新上架',
  PRIMARY KEY (`ID`),
  -- 刻意不含 DATA_STATUS：编码是对外业务标识，删除后复用会让历史追溯串号
  UNIQUE KEY `uk_mall_category_code` (`CATEGORY_CODE`),
  INDEX `idx_mall_category_status` (`CATEGORY_STATUS`, `CATEGORY_SORT`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城商品分类表（E2E-09 S1）';

-- ----------------------------
-- 商品 SPU：展示主体；定价与库存在 SKU
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_product`;
CREATE TABLE `ws_mall_product` (
  `ID`                bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`       tinyint       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`         bigint        NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`       varchar(14)   NOT NULL COMMENT '创建时间',
  `UPDATE_BY`         bigint        NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`       varchar(14)   NOT NULL COMMENT '更新时间',
  `PRODUCT_NO`        varchar(50)   NOT NULL COMMENT '商品业务编号(max50)：唯一，不复用',
  `CATEGORY_ID`       bigint        NOT NULL COMMENT '分类ID（ws_mall_category.ID）',
  `PRODUCT_NAME`      varchar(100)  NOT NULL COMMENT '商品名称(max100)',
  `PRODUCT_SUBTITLE`  varchar(200)  COMMENT '副标题(max200)',
  `COVER_URL`         varchar(500)  COMMENT '主图（既有合法 URL/相对资源路径；本期无上传能力）',
  `PRODUCT_DESC`      text          COMMENT '商品说明（纯文本/受控富文本，本期纯文本展示）',
  `PRODUCT_STATUS`    tinyint       NOT NULL COMMENT '商品状态(1388)：1草稿 2已上架 3已下架；上架前置校验见契约四',
  `VERSION`           int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本：上下架 CAS 用',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_product_no` (`PRODUCT_NO`),
  INDEX `idx_mall_product_category` (`CATEGORY_ID`, `PRODUCT_STATUS`),
  INDEX `idx_mall_product_status` (`PRODUCT_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城商品SPU表（E2E-09 S1）';

-- ----------------------------
-- SKU：定价与库存主体
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_sku`;
CREATE TABLE `ws_mall_sku` (
  `ID`            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`   tinyint       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除；被库存/订单引用的 SKU 禁物理删除',
  `CREATE_BY`     bigint        NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`   varchar(14)   NOT NULL COMMENT '创建时间',
  `UPDATE_BY`     bigint        NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`   varchar(14)   NOT NULL COMMENT '更新时间',
  `SKU_NO`        varchar(50)   NOT NULL COMMENT 'SKU业务编号(max50)：唯一，不复用',
  `PRODUCT_ID`    bigint        NOT NULL COMMENT '商品ID（ws_mall_product.ID）',
  `SKU_NAME`      varchar(100)  NOT NULL COMMENT 'SKU名称(max100)',
  `SPEC_SNAP`     varchar(500)  NOT NULL COMMENT '规格快照 JSON(max500)：扁平 string→string 对象（如 {"规格":"500ml×12","口味":"原味"}），≤8键，服务端唯一校验；owner=商城域；演进只增键不改语义',
  `SALE_PRICE`    bigint        NOT NULL COMMENT '售价(分)：正整数',
  `MARKET_PRICE`  bigint        COMMENT '划线价(分)：可空；有值时 ≥ SALE_PRICE',
  `WEIGHT_GRAM`   bigint        NOT NULL DEFAULT 0 COMMENT '重量(克)：非负整数',
  `SKU_STATUS`    tinyint       NOT NULL COMMENT 'SKU状态(1389)：1启用 2停用；停用不删历史库存与流水',
  `VERSION`       int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_sku_no` (`SKU_NO`),
  INDEX `idx_mall_sku_product` (`PRODUCT_ID`, `SKU_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城SKU表（E2E-09 S1）';

-- ----------------------------
-- 前置仓
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_warehouse`;
CREATE TABLE `ws_mall_warehouse` (
  `ID`                 bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`        tinyint       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`          bigint        NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`        varchar(14)   NOT NULL COMMENT '创建时间',
  `UPDATE_BY`          bigint        NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`        varchar(14)   NOT NULL COMMENT '更新时间',
  `WAREHOUSE_NO`       varchar(50)   NOT NULL COMMENT '前置仓业务编号(max50)：唯一，不复用',
  `WAREHOUSE_NAME`     varchar(100)  NOT NULL COMMENT '前置仓名称(max100)',
  `CONTACT_NAME`       varchar(50)   NOT NULL COMMENT '联系人(max50)',
  `CONTACT_PHONE`      varchar(20)   NOT NULL COMMENT '联系电话(max20)：列表/详情脱敏展示，原文不进小程序响应',
  `PROVINCE_CODE`      varchar(6)    NOT NULL COMMENT '省行政区码(6位)',
  `CITY_CODE`          varchar(6)    NOT NULL COMMENT '市行政区码(6位)',
  `DISTRICT_CODE`      varchar(6)    NOT NULL COMMENT '区行政区码(6位)',
  `WAREHOUSE_ADDRESS`  varchar(200)  NOT NULL COMMENT '详细地址(max200)',
  `LONGITUDE`          varchar(20)   COMMENT '经度（沿用水站合同字符串精度，接真地图前不改型）',
  `LATITUDE`           varchar(20)   COMMENT '纬度（同上）',
  `SERVICE_SCOPE_JSON` varchar(1000) NOT NULL COMMENT '履约范围 JSON(max1000)：{"scopeType":"districts","districtCodes":["420111"]}；服务端唯一校验（六位码/非空/去重）；owner=商城域；S2 履约按此过滤；原文不下发小程序',
  `WAREHOUSE_STATUS`   tinyint       NOT NULL COMMENT '前置仓状态(1390)：1启用 2停用；停用仓不参与小程序可售聚合',
  `VERSION`            int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_warehouse_no` (`WAREHOUSE_NO`),
  INDEX `idx_mall_warehouse_status` (`WAREHOUSE_STATUS`),
  INDEX `idx_mall_warehouse_district` (`DISTRICT_CODE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城前置仓表（E2E-09 S1）';

-- ----------------------------
-- 库存：仓×SKU 一行；一切扣减原子条件 UPDATE
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_stock`;
CREATE TABLE `ws_mall_stock` (
  `ID`             bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`    tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：库存行恒0，不做业务逻辑删除',
  `CREATE_BY`      bigint      NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`    varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`      bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`    varchar(14) NOT NULL COMMENT '更新时间',
  `WAREHOUSE_ID`   bigint      NOT NULL COMMENT '前置仓ID（ws_mall_warehouse.ID）',
  `SKU_ID`         bigint      NOT NULL COMMENT 'SKU ID（ws_mall_sku.ID）',
  `AVAILABLE_QTY`  bigint      NOT NULL DEFAULT 0 COMMENT '可售数量(件)：恒≥0；扣减必须原子条件 UPDATE（WHERE AVAILABLE_QTY>=q），禁止读出后内存计算回写',
  `RESERVED_QTY`   bigint      NOT NULL DEFAULT 0 COMMENT '预占数量(件)：恒≥0；本期恒0，S2 下单预占启用（AVAILABLE→RESERVED 原子迁移）',
  `VERSION`        int         NOT NULL DEFAULT 1 COMMENT '乐观锁版本：随每次库存动作+1',
  PRIMARY KEY (`ID`),
  -- 并发安全唯一锚：同仓同 SKU 恒一行，确保原子 UPDATE 定位唯一
  UNIQUE KEY `uk_mall_stock_wh_sku` (`WAREHOUSE_ID`, `SKU_ID`),
  INDEX `idx_mall_stock_sku` (`SKU_ID`),
  -- 负库存库层硬闸（R1-P2-1）：条件 UPDATE 之外的任何原始写入同样不可为负
  CONSTRAINT `chk_mall_stock_available_nonneg` CHECK (`AVAILABLE_QTY` >= 0),
  CONSTRAINT `chk_mall_stock_reserved_nonneg` CHECK (`RESERVED_QTY` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城库存表（仓×SKU，E2E-09 S1）';

-- ----------------------------
-- 库存流水：只增不改；幂等键=库层闸
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_stock_flow`;
CREATE TABLE `ws_mall_stock_flow` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：流水恒0；即使被误标删除，幂等键也不复用（唯一键不含本列）',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID（=操作人）',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间（动作时刻）',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID（只增表，恒=创建人）',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间（只增表，恒=创建时间）',
  `BIZ_IDEMPOTENCY_KEY` varchar(64)  NOT NULL COMMENT '幂等键(max64)：人工动作=MALLADJ:<requestId>；S2 起预占/实销/释放/回库=MALLRSV:<orderNo> 族',
  `WAREHOUSE_ID`        bigint       NOT NULL COMMENT '前置仓ID',
  `SKU_ID`              bigint       NOT NULL COMMENT 'SKU ID',
  `FLOW_TYPE`           tinyint      NOT NULL COMMENT '流水类型(1391)：1人工入库 2人工出库 3盘点调增 4盘点调减；5~8 预留（预占/释放/实销/退货回库）',
  `AVAILABLE_CHANGE`    bigint       NOT NULL COMMENT '可售变化量(件)：带符号',
  `RESERVED_CHANGE`     bigint       NOT NULL COMMENT '预占变化量(件)：带符号；本期恒0',
  `AVAILABLE_AFTER`     bigint       NOT NULL COMMENT '动作后可售终值(件)：与库存行同事务一致',
  `RESERVED_AFTER`      bigint       NOT NULL COMMENT '动作后预占终值(件)',
  `FLOW_REASON`         varchar(200) NOT NULL COMMENT '动作原因(max200)：必填',
  `OPERATOR_ID`         bigint       NOT NULL COMMENT '操作人ID（管理端账号）',
  PRIMARY KEY (`ID`),
  -- 刻意不含 DATA_STATUS：幂等键是资金级防重锚，逻辑删除不解锁重放
  UNIQUE KEY `uk_mall_stock_flow_biz_key` (`BIZ_IDEMPOTENCY_KEY`),
  INDEX `idx_mall_stock_flow_wh_sku` (`WAREHOUSE_ID`, `SKU_ID`),
  INDEX `idx_mall_stock_flow_sku` (`SKU_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城库存流水表（只增，E2E-09 S1）';

-- ============================================================
-- S2 交易五表（购物车 / 订单 / 明细 / 支付单 / 支付事实收件箱）
-- 与一期 ws_order/ws_payment/ws_payment_event 完全分域：商城订单 ID 与 ws_order.ID
-- 存在碰撞可能，复用一期支付单会把两域订单混指同一行——故独立建表、独立唯一键。
-- ============================================================

-- ----------------------------
-- 购物车行：一人一 SKU 恒一行。唯一键刻意不含 DATA_STATUS——移除后再次加购必须
-- 复活同一行（服务层走 upsert 重置 DATA_STATUS=0），否则第二次加购撞键报错。
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_cart_item`;
CREATE TABLE `ws_mall_cart_item` (
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
-- 商城订单：一单一仓（选仓结果创单冻结，履约期间不再重选）。
-- 收货信息为下单时刻快照——地址簿后续被改或被删都不影响已下订单的履约事实。
-- 金额恒等式由库层 CHECK 兜住：篡改任一列都写不进去。
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_order`;
CREATE TABLE `ws_mall_order` (
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
  `SOURCE_AFTER_SALE_ID`   bigint       COMMENT '换货补发来源售后单ID：非空即内部零价补发单，不计新销售收入；普通订单恒 NULL。唯一键保证一张售后单只补发一次',
  `VERSION`                int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本：每次状态迁移+1，CAS 校验影响行数',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_order_source_after_sale` (`SOURCE_AFTER_SALE_ID`),
  UNIQUE KEY `uk_mall_order_no` (`ORDER_NO`),
  -- 创单幂等锚：同一用户同一请求号恒一单；刻意不含 DATA_STATUS
  UNIQUE KEY `uk_mall_order_user_request` (`USER_ID`, `REQUEST_ID`),
  INDEX `idx_mall_order_user_status` (`USER_ID`, `ORDER_STATUS`),
  -- 超时关单 Worker 扫描路径：状态+到期时间
  INDEX `idx_mall_order_status_expire` (`ORDER_STATUS`, `PAY_EXPIRE_TIME`),
  INDEX `idx_mall_order_warehouse` (`WAREHOUSE_ID`),
  CONSTRAINT `chk_mall_order_amount_nonneg` CHECK (`PRODUCT_AMOUNT_FEN` >= 0 AND `DELIVERY_FEE_FEN` >= 0),
  CONSTRAINT `chk_mall_order_amount_sum` CHECK (`ORDER_AMOUNT_FEN` = `PRODUCT_AMOUNT_FEN` + `DELIVERY_FEE_FEN`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城订单表（E2E-09 S2）';

-- ----------------------------
-- 订单明细：下单即冻结商品/SKU/规格/单价/重量快照，商品后续改名改价不影响历史订单。
-- 行金额恒等式由库层 CHECK 兜住（单价×数量），与订单金额恒等式共同封死前端传金额路径。
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_order_item`;
CREATE TABLE `ws_mall_order_item` (
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
  -- 一单一 SKU 恒一行：同 SKU 重复加购在创单前已合并数量
  UNIQUE KEY `uk_mall_order_item_order_sku` (`ORDER_ID`, `SKU_ID`),
  INDEX `idx_mall_order_item_order` (`ORDER_ID`),
  INDEX `idx_mall_order_item_sku` (`SKU_ID`),
  CONSTRAINT `chk_mall_order_item_qty_positive` CHECK (`QUANTITY` > 0 AND `QUANTITY` <= 999),
  CONSTRAINT `chk_mall_order_item_price_nonneg` CHECK (`UNIT_PRICE_FEN` >= 0),
  CONSTRAINT `chk_mall_order_item_amount` CHECK (`ITEM_AMOUNT_FEN` = `UNIT_PRICE_FEN` * `QUANTITY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城订单明细表（E2E-09 S2）';

-- ----------------------------
-- 商城支付单：一单一支付单（库层三唯一键挡住"一单多支付单"与重复交易号），
-- 形态对齐一期 ws_payment，但 ORDER_ID 指向 ws_mall_order，两域绝不互指。
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_payment`;
CREATE TABLE `ws_mall_payment` (
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
  -- 三条唯一键是资金安全线：重复交易号、一单多支付单在库层即不可达
  UNIQUE KEY `uk_mall_payment_transaction` (`TRANSACTION_ID`),
  UNIQUE KEY `uk_mall_payment_order_no` (`ORDER_NO`),
  UNIQUE KEY `uk_mall_payment_order_id` (`ORDER_ID`),
  CONSTRAINT `chk_mall_payment_amount_nonneg` CHECK (`PAY_AMOUNT_FEN` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城支付单表（E2E-09 S2）';

-- ----------------------------
-- 商城支付事实收件箱：外部支付事实只落此表，资金推进另起事务（两段式）。
-- 形态对齐一期 ws_payment_event。RAW_BODY_SHA256 是"完整性摘要"——只证明正文未被
-- 篡改地改写过，不是不可抵赖证明，不得当作签名效力使用。
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_payment_fact`;
CREATE TABLE `ws_mall_payment_fact` (
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
  -- 幂等锚：同源同渠道同事实键恒一行；刻意不含 DATA_STATUS——误删不解锁重放
  UNIQUE KEY `uk_mall_payment_fact_key` (`PAY_SOURCE`, `FACT_CHANNEL`, `PROVIDER_EVENT_KEY`),
  INDEX `idx_mall_payment_fact_claim` (`PROCESSING_STATUS`, `NEXT_RETRY_TIME`),
  INDEX `idx_mall_payment_fact_order_no` (`ORDER_NO`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城支付事实收件箱表（E2E-09 S2）';

-- ----------------------------
-- 字典（1388~1392 + 1363 增值 11 商城动作；S2 增 1393~1395 与 1391 的 5~8）
-- ----------------------------
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (
            SELECT '商城商品状态' AS DICT_NAME, '1388' AS DICT_TYPE, '商城SPU上下架状态' AS DICT_REMARK
  UNION ALL SELECT '商城SKU状态', '1389', '商城SKU启停状态'
  UNION ALL SELECT '前置仓状态', '1390', '商城前置仓启停状态'
  UNION ALL SELECT '商城库存流水类型', '1391', '商城库存动作类型（1~4人工动作，5~8订单流转）'
  UNION ALL SELECT '商城分类状态', '1392', '商城商品分类启停状态'
  UNION ALL SELECT '商城订单状态', '1393', '商城订单状态机'
  UNION ALL SELECT '商城支付状态', '1394', '商城支付单状态'
  UNION ALL SELECT '商城支付事实处理状态', '1395', '商城支付事实收件箱处理状态'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1388' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '草稿' AS DICT_LABEL
  UNION ALL SELECT '1388', 2, 2, '已上架'
  UNION ALL SELECT '1388', 3, 3, '已下架'
  UNION ALL SELECT '1389', 1, 1, '启用'
  UNION ALL SELECT '1389', 2, 2, '停用'
  UNION ALL SELECT '1390', 1, 1, '启用'
  UNION ALL SELECT '1390', 2, 2, '停用'
  UNION ALL SELECT '1391', 1, 1, '人工入库'
  UNION ALL SELECT '1391', 2, 2, '人工出库'
  UNION ALL SELECT '1391', 3, 3, '盘点调增'
  UNION ALL SELECT '1391', 4, 4, '盘点调减'
  UNION ALL SELECT '1391', 5, 5, '下单预占'
  UNION ALL SELECT '1391', 6, 6, '预占释放'
  UNION ALL SELECT '1391', 7, 7, '支付实销'
  UNION ALL SELECT '1391', 8, 8, '退货回库'
  UNION ALL SELECT '1391', 9, 9, '换货预占'
  UNION ALL SELECT '1391', 10, 10, '换货出库'
  UNION ALL SELECT '1391', 11, 11, '换货释放'
  UNION ALL SELECT '1392', 1, 1, '启用'
  UNION ALL SELECT '1392', 2, 2, '停用'
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
  UNION ALL SELECT '1363', 11, 11, '商城动作'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);


-- ----------------------------
-- 字典：1396~1403（E2E-09 S3/S4）
-- 这两批当初只落了 init 与迁移，领域源漏同步；空库从本文件建出来会缺八个编号，
-- L1 一并补齐，让四轨真的是四轨。幂等写法同下：NOT EXISTS，不用 IGNORE。
-- ----------------------------
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.DICT_NAME, s.DICT_TYPE, s.DICT_REMARK FROM (
            SELECT '商城履约任务状态' AS DICT_NAME, '1396' AS DICT_TYPE, '商城前置仓履约与配送签收状态机' AS DICT_REMARK
  UNION ALL SELECT '商城履约操作方', '1397', '商城履约轨迹节点的操作方'
  UNION ALL SELECT '商城签收方式', '1398', '商城订单签收方式'
  UNION ALL SELECT '商城售后状态', '1399', '商城退货退款与换货状态机'
  UNION ALL SELECT '商城售后类型', '1400', '商城售后申请类型'
  UNION ALL SELECT '商城质检结论', '1401', '商城退货质检结论'
  UNION ALL SELECT '商城退款状态', '1402', '商城退款单状态'
  UNION ALL SELECT '商城退款事实处理状态', '1403', '商城退款事实收件箱处理状态'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1396' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '待拣货' AS DICT_LABEL
  UNION ALL SELECT '1396', 2, 2, '待打包'
  UNION ALL SELECT '1396', 3, 3, '待安排发运'
  UNION ALL SELECT '1396', 4, 4, '待承运方揽收'
  UNION ALL SELECT '1396', 5, 5, '运输中'
  UNION ALL SELECT '1396', 6, 6, '已送达待确认'
  UNION ALL SELECT '1396', 7, 7, '已签收'
  UNION ALL SELECT '1397', 1, 1, '系统'
  UNION ALL SELECT '1397', 2, 2, '前置仓'
  UNION ALL SELECT '1397', 3, 3, '配送员'
  UNION ALL SELECT '1397', 4, 4, '用户'
  UNION ALL SELECT '1398', 1, 1, '本人签收'
  UNION ALL SELECT '1398', 2, 2, '他人代收'
  UNION ALL SELECT '1399', 1, 1, '待审核'
  UNION ALL SELECT '1399', 2, 2, '待退货'
  UNION ALL SELECT '1399', 3, 3, '待质检'
  UNION ALL SELECT '1399', 4, 4, '退款处理中'
  UNION ALL SELECT '1399', 5, 5, '换货补发中'
  UNION ALL SELECT '1399', 6, 6, '已完成'
  UNION ALL SELECT '1399', 7, 7, '已驳回'
  UNION ALL SELECT '1399', 8, 8, '待人工'
  UNION ALL SELECT '1399', 9, 9, '已取消'
  UNION ALL SELECT '1400', 1, 1, '退货退款'
  UNION ALL SELECT '1400', 2, 2, '同SKU换货'
  UNION ALL SELECT '1400', 3, 3, '未拣货整单取消退款'
  UNION ALL SELECT '1401', 1, 1, '通过可重新销售'
  UNION ALL SELECT '1401', 2, 2, '通过不可重新销售'
  UNION ALL SELECT '1401', 3, 3, '不通过'
  UNION ALL SELECT '1402', 1, 1, '待退款'
  UNION ALL SELECT '1402', 2, 2, '退款成功'
  UNION ALL SELECT '1402', 3, 3, '退款失败'
  UNION ALL SELECT '1402', 4, 4, '已关闭'
  UNION ALL SELECT '1403', 1, 1, '待处理'
  UNION ALL SELECT '1403', 2, 2, '处理中'
  UNION ALL SELECT '1403', 3, 3, '已处理'
  UNION ALL SELECT '1403', 4, 4, '待重试'
  UNION ALL SELECT '1403', 5, 5, '需对账'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

-- ----------------------------
-- 字典：1404~1409（E2E-09 L1 多渠道物流）
-- 幂等只能用 NOT EXISTS：这两张表除主键外无唯一键，IGNORE 无键可撞、重复执行会翻倍，
-- 而字典查询是 selectJoinOne，遇重复行直接 TooManyResults 让整个编号返回 500（本仓踩过两次）。
-- ----------------------------
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.DICT_NAME, s.DICT_TYPE, s.DICT_REMARK FROM (
            SELECT '商城履约渠道' AS DICT_NAME, '1404' AS DICT_TYPE, '自营配送与第三方物流的渠道冻结值' AS DICT_REMARK
  UNION ALL SELECT '商城出库包裹方向', '1405', '正向发货/退货/换货补发'
  UNION ALL SELECT '商城出库包裹状态', '1406', '渠道中立的包裹生命周期'
  UNION ALL SELECT '商城物流事件状态', '1407', '承运方事件白名单'
  UNION ALL SELECT '商城物流处理状态', '1408', '物流事件收件箱与出站动作共用值域'
  UNION ALL SELECT '商城物流动作类型', '1409', '出站动作：创建运单/取消运单'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1404' AS DICT_TYPE, 0 AS DICT_SORT, 0 AS DICT_VALUE, '未确定' AS DICT_LABEL
  UNION ALL SELECT '1404', 1, 1, '自营配送'
  UNION ALL SELECT '1404', 2, 2, '第三方物流'
  UNION ALL SELECT '1405', 1, 1, '正向发货'
  UNION ALL SELECT '1405', 2, 2, '退货'
  UNION ALL SELECT '1405', 3, 3, '换货补发'
  UNION ALL SELECT '1406', 1, 1, '待发运'
  UNION ALL SELECT '1406', 2, 2, '已受理'
  UNION ALL SELECT '1406', 3, 3, '已揽收'
  UNION ALL SELECT '1406', 4, 4, '运输中'
  UNION ALL SELECT '1406', 5, 5, '已送达'
  UNION ALL SELECT '1406', 6, 6, '已签收'
  UNION ALL SELECT '1406', 7, 7, '已取消'
  UNION ALL SELECT '1406', 8, 8, '异常待人工'
  UNION ALL SELECT '1407', 1, 1, '已接单'
  UNION ALL SELECT '1407', 2, 2, '已揽收'
  UNION ALL SELECT '1407', 3, 3, '运输中'
  UNION ALL SELECT '1407', 4, 4, '已送达'
  UNION ALL SELECT '1407', 5, 5, '已签收'
  UNION ALL SELECT '1407', 6, 6, '异常'
  UNION ALL SELECT '1407', 7, 7, '已取消'
  UNION ALL SELECT '1408', 1, 1, '待处理'
  UNION ALL SELECT '1408', 2, 2, '处理中'
  UNION ALL SELECT '1408', 3, 3, '已处理'
  UNION ALL SELECT '1408', 4, 4, '待重试'
  UNION ALL SELECT '1408', 5, 5, '需人工'
  UNION ALL SELECT '1409', 1, 1, '创建运单'
  UNION ALL SELECT '1409', 2, 2, '取消运单'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);


-- ----------------------------
-- 演示种子（覆盖草稿/上架/下架/缺货/停用仓/停用SKU/停用分类；假手机号）
-- 追溯关系：库存终值与流水 AFTER 自洽（见 ws_mall_stock_flow 种子）
-- ----------------------------
INSERT IGNORE INTO `ws_mall_category`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`CATEGORY_CODE`,`CATEGORY_NAME`,`CATEGORY_SORT`,`CATEGORY_STATUS`) VALUES
(1,0,1,'20260808120000',1,'20260808120000','MC-DRINK','包装饮用水',1,1),
(2,0,1,'20260808120000',1,'20260808120000','MC-DEVICE','净水耗材',2,1),
(3,0,1,'20260808120000',1,'20260808120000','MC-GIFT','礼品周边',3,2);

INSERT IGNORE INTO `ws_mall_product`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`PRODUCT_NO`,`CATEGORY_ID`,`PRODUCT_NAME`,`PRODUCT_SUBTITLE`,`COVER_URL`,`PRODUCT_DESC`,`PRODUCT_STATUS`,`VERSION`) VALUES
(1,0,1,'20260808120000',1,'20260808120000','MP-2026-0001',1,'六维达康天然饮用水 整箱','500ml×12 瓶装 家庭常备',NULL,'水源地直供，整箱配送到家。',2,2),
(2,0,1,'20260808120000',1,'20260808120000','MP-2026-0002',1,'六维达康桶装水 18.9L','空桶可换 押金另计',NULL,'18.9L 家用桶装水，前置仓就近履约。',2,2),
(3,0,1,'20260808120000',1,'20260808120000','MP-2026-0003',2,'家用净水器滤芯 PP棉','三个月更换周期',NULL,'适配六维达康家用净水系列。',2,2),
(4,0,1,'20260808120000',1,'20260808120000','MP-2026-0004',2,'净水器复合滤芯 RO膜','高端机型适配',NULL,'RO 反渗透复合滤芯（草稿示例）。',1,1),
(5,0,1,'20260808120000',1,'20260808120000','MP-2026-0005',3,'达康定制保温杯','曾上架现已下架',NULL,'316 不锈钢保温杯（已下架示例）。',3,3);

INSERT IGNORE INTO `ws_mall_sku`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`SKU_NO`,`PRODUCT_ID`,`SKU_NAME`,`SPEC_SNAP`,`SALE_PRICE`,`MARKET_PRICE`,`WEIGHT_GRAM`,`SKU_STATUS`,`VERSION`) VALUES
(1,0,1,'20260808120000',1,'20260808120000','MS-2026-0001',1,'整箱 500ml×12','{"规格":"500ml×12"}',2400,2990,6600,1,1),
(2,0,1,'20260808120000',1,'20260808120000','MS-2026-0002',1,'整箱 500ml×24','{"规格":"500ml×24"}',4500,5800,13200,1,1),
(3,0,1,'20260808120000',1,'20260808120000','MS-2026-0003',2,'单桶 18.9L','{"规格":"18.9L"}',1800,NULL,19500,1,1),
(4,0,1,'20260808120000',1,'20260808120000','MS-2026-0004',2,'五桶套票 18.9L×5','{"规格":"18.9L×5","形态":"套票"}',8500,9000,0,2,1),
(5,0,1,'20260808120000',1,'20260808120000','MS-2026-0005',3,'PP棉滤芯 单支','{"规格":"单支","适配":"家用一代"}',3900,4500,350,1,1),
(6,0,1,'20260808120000',1,'20260808120000','MS-2026-0006',3,'PP棉滤芯 三支装','{"规格":"三支装","适配":"家用一代"}',9900,13500,1050,1,1),
(7,0,1,'20260808120000',1,'20260808120000','MS-2026-0007',4,'RO膜滤芯 单支','{"规格":"单支","适配":"旗舰机"}',19900,25900,900,1,1),
(8,0,1,'20260808120000',1,'20260808120000','MS-2026-0008',5,'保温杯 500ml 银','{"容量":"500ml","颜色":"银"}',6900,8900,320,1,1);

INSERT IGNORE INTO `ws_mall_warehouse`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`WAREHOUSE_NO`,`WAREHOUSE_NAME`,`CONTACT_NAME`,`CONTACT_PHONE`,`PROVINCE_CODE`,`CITY_CODE`,`DISTRICT_CODE`,`WAREHOUSE_ADDRESS`,`LONGITUDE`,`LATITUDE`,`SERVICE_SCOPE_JSON`,`WAREHOUSE_STATUS`,`VERSION`) VALUES
(1,0,1,'20260808120000',1,'20260808120000','MW-WH-001','光谷前置仓','周仓管','13500005555','420000','420100','420111','武汉市洪山区光谷大道 77 号','114.4180','30.4680','{"scopeType":"districts","districtCodes":["420111","420114"]}',1,1),
(2,0,1,'20260808120000',1,'20260808120000','MW-WH-002','汉口前置仓（停用示例）','吴仓管','13500006666','420000','420100','420102','武汉市江岸区解放大道 1001 号','114.3050','30.5960','{"scopeType":"districts","districtCodes":["420102"]}',2,1),
(3,0,1,'20260808120000',1,'20260808120000','MW-WH-003','南湖前置仓','郑仓管','13500007777','420000','420100','420111','武汉市洪山区南湖大道 12 号','114.3420','30.4880','{"scopeType":"districts","districtCodes":["420111"]}',1,1);

-- 库存种子：SKU1 双仓有货；SKU2 仅停用仓有货（端上应显缺货）；SKU3 有货；
-- SKU5 有货、SKU6 零库存（缺货示例）；SKU7/8（草稿/下架商品）留少量库存不影响端上
INSERT IGNORE INTO `ws_mall_stock`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`WAREHOUSE_ID`,`SKU_ID`,`AVAILABLE_QTY`,`RESERVED_QTY`,`VERSION`) VALUES
(1,0,1,'20260808120000',1,'20260808120000',1,1,120,0,2),
(2,0,1,'20260808120000',1,'20260808120000',3,1,45,0,2),
(3,0,1,'20260808120000',1,'20260808120000',2,2,60,0,2),
(4,0,1,'20260808120000',1,'20260808120000',1,3,200,0,3),
(5,0,1,'20260808120000',1,'20260808120000',1,5,80,0,2),
(6,0,1,'20260808120000',1,'20260808120000',3,6,0,0,3),
(7,0,1,'20260808120000',1,'20260808120000',1,7,10,0,2),
(8,0,1,'20260808120000',1,'20260808120000',1,8,15,0,2);

-- 流水种子：每行库存的建行入库轨迹，AFTER 与上表终值一致（SKU3 含一次出库、SKU6 出清）
INSERT IGNORE INTO `ws_mall_stock_flow`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`BIZ_IDEMPOTENCY_KEY`,`WAREHOUSE_ID`,`SKU_ID`,`FLOW_TYPE`,`AVAILABLE_CHANGE`,`RESERVED_CHANGE`,`AVAILABLE_AFTER`,`RESERVED_AFTER`,`FLOW_REASON`,`OPERATOR_ID`) VALUES
(1,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0001',1,1,1,120,0,120,0,'首批入库',1),
(2,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0002',3,1,1,45,0,45,0,'首批入库',1),
(3,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0003',2,2,1,60,0,60,0,'首批入库（停用仓示例）',1),
(4,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0004',1,3,1,220,0,220,0,'首批入库',1),
(5,0,1,'20260808120200',1,'20260808120200','MALLADJ:seed-0005',1,3,2,-20,0,200,0,'破损出库',1),
(6,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0006',1,5,1,80,0,80,0,'首批入库',1),
(7,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0007',3,6,1,30,0,30,0,'首批入库',1),
(8,0,1,'20260808120200',1,'20260808120200','MALLADJ:seed-0008',3,6,2,-30,0,0,0,'临期出清',1),
(9,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0009',1,7,1,10,0,10,0,'样品入库',1),
(10,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0010',1,8,1,15,0,15,0,'尾货入库',1);

-- ----------------------------
-- 商城履约任务（E2E-09 S3）：一单一任务；收货信息为订单冻结快照
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_fulfillment`;
CREATE TABLE `ws_mall_fulfillment` (
  `ID`                     bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`            tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`              bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`            varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`              bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`            varchar(14)  NOT NULL COMMENT '更新时间',
  `ORDER_ID`               bigint       NOT NULL COMMENT '商城订单ID（ws_mall_order.ID）；一单一任务由 uk 兜底',
  `ORDER_NO`               varchar(32)  NOT NULL COMMENT '商城订单号快照：三端与本表共用同一编号，履约刻意不另发展示号，避免用户/仓库/配送员各报一个号对不上',
  `USER_ID`                bigint       NOT NULL COMMENT '收货用户ID（ws_user.ID）；分配配送员时据此拒绝自己给自己配送',
  `WAREHOUSE_ID`           bigint       NOT NULL COMMENT '前置仓ID快照（ws_mall_warehouse.ID）；仓库范围判定只吃本列，不回查订单',
  `COURIER_ID`             bigint       COMMENT '配送员ID（ws_courier.ID）；未分配为空，分配 CAS 以 IS NULL 为前置',
  `FULFILL_STATUS`         tinyint      NOT NULL COMMENT '履约状态(1396)：1待拣货 2待打包 3待安排发运 4待承运方揽收 5运输中 6已送达待确认 7已签收（渠道中立，自营与第三方共用）',
  `FULFILL_MODE`           tinyint      NOT NULL DEFAULT 0 COMMENT '履约渠道(1404)：0未确定 1自营配送 2第三方物流；由分配配送员或创建运单 CAS 冻结，冻结后不可改',
  `VERSION`                int          NOT NULL COMMENT '乐观锁版本：创建=1，每次状态转换+1；所有转换 WHERE VERSION=期望值',
  `RECEIVER_NAME`          varchar(30)  NOT NULL COMMENT '收货人快照(max30)：取自订单冻结快照，履约期间不回查地址表——地址改了不能改已发出的货',
  `RECEIVER_PHONE`         varchar(11)  NOT NULL COMMENT '收货电话快照(max11)：展示必须脱敏，非本人配送员一律不下发',
  `RECEIVER_REGION`        varchar(100) NOT NULL COMMENT '收货区域快照(max100)',
  `RECEIVER_ADDRESS`       varchar(200) NOT NULL COMMENT '收货详细地址快照(max200)',
  `RECEIVER_DISTRICT_CODE` varchar(6)   NOT NULL COMMENT '收货区县码快照(6位)',
  `PICK_TIME`              varchar(14)  COMMENT '拣货时间（履约开始，订单同事务 2→3）',
  `PACK_TIME`              varchar(14)  COMMENT '打包完成时间',
  `ASSIGN_TIME`            varchar(14)  COMMENT '分配配送员时间',
  `FETCH_TIME`             varchar(14)  COMMENT '配送员取货时间',
  `ARRIVE_TIME`            varchar(14)  COMMENT '送达时间',
  `SIGN_TIME`              varchar(14)  COMMENT '签收时间（服务端生成，非客户端上传；与订单完成时间、轨迹与消息同源。审计事件不在此列——领域事件服务无业务时间入参）',
  `SIGN_METHOD`            tinyint      COMMENT '签收方式(1398)：1本人签收 2他人代收；签收前为空',
  `SIGN_REMARK`            varchar(200) COMMENT '签收备注(max200)：S3 的签收证据为结构化文本，照片证据待对象存储接入后再议',
  `FULFILL_REMARK`         varchar(500) COMMENT '履约备注(max500)',
  PRIMARY KEY (`ID`),
  -- 一单一任务是履约安全键：并发重复建任务必须由库层挡住，不能依赖代码查重。
  -- 刻意不含 DATA_STATUS——任务被误删后重建会绕开唯一性，等于一单两任务。
  UNIQUE KEY `uk_mall_fulfill_order` (`ORDER_ID`),
  INDEX `idx_mall_fulfill_courier_status` (`COURIER_ID`, `FULFILL_STATUS`),
  INDEX `idx_mall_fulfill_wh_status` (`WAREHOUSE_ID`, `FULFILL_STATUS`),
  INDEX `idx_mall_fulfill_order_no` (`ORDER_NO`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城履约任务表（E2E-09 S3）';

-- ----------------------------
-- 商城履约轨迹（E2E-09 S3）：节点即状态到达史，幂等键防重复点击重复写
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_fulfillment_trace`;
CREATE TABLE `ws_mall_fulfillment_trace` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间',
  `FULFILL_ID`          bigint       NOT NULL COMMENT '履约任务ID（ws_mall_fulfillment.ID）',
  `ORDER_NO`            varchar(32)  NOT NULL COMMENT '商城订单号快照：三端时间线按本列对齐',
  `TRACE_NODE`          tinyint      NOT NULL COMMENT '到达节点(1396)：与任务状态同值域，轨迹即状态到达史',
  `ACTOR_TYPE`          tinyint      NOT NULL COMMENT '操作方(1397)：1系统 2前置仓 3配送员 4用户',
  `ACTOR_ID`            bigint       NOT NULL COMMENT '操作人ID；系统动作写 0',
  `SUBJECT_ID`          bigint       COMMENT '节点业务对象ID：分配节点记录被分配配送员ID。与 ACTOR_ID 刻意分列——ACTOR_ID 是「谁做的」（分配节点为仓库操作员），SUBJECT_ID 是「做给谁」；混用会让配送归属无从核验',
  `TRACE_TIME`          varchar(14)  NOT NULL COMMENT '节点时间：与任务时间、订单时间与消息同源（审计事件时间由领域事件服务自行生成，不同源）',
  `TRACE_TEXT`          varchar(200) NOT NULL COMMENT '节点文案(max200)',
  `BIZ_IDEMPOTENCY_KEY` varchar(64)  NOT NULL COMMENT '幂等键：MFT:<orderNo>:<node>。重复点击撞键即跳过，这是"重复推进不得重复写轨迹"的库层保证',
  PRIMARY KEY (`ID`),
  -- 不含 DATA_STATUS：删掉一条轨迹不该让同一节点可以再写一遍
  UNIQUE KEY `uk_mall_ftrace_key` (`BIZ_IDEMPOTENCY_KEY`),
  INDEX `idx_mall_ftrace_fulfill` (`FULFILL_ID`),
  INDEX `idx_mall_ftrace_order_no` (`ORDER_NO`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城履约轨迹表（E2E-09 S3）';

-- ----------------------------
-- 商城配送员前置仓服务范围（E2E-09 S3）：让「范围外」成为可判定事实
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_courier_scope`;
CREATE TABLE `ws_mall_courier_scope` (
  `ID`           bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`  tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0生效 1解除',
  `CREATE_BY`    bigint      NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`  varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`    bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`  varchar(14) NOT NULL COMMENT '更新时间',
  `WAREHOUSE_ID` bigint      NOT NULL COMMENT '前置仓ID（ws_mall_warehouse.ID）',
  `COURIER_ID`   bigint      NOT NULL COMMENT '配送员ID（ws_courier.ID）',
  PRIMARY KEY (`ID`),
  -- 一期 ws_courier 的服务范围是水站集（STATION_IDS）与自由文本 SERVICE_REGION，
  -- 与商城前置仓不是同一套坐标系；按文本猜区域正是 DISTRICT_CODE 那次的教训。
  -- 故商城域自持这张绑定表：配送员服务哪些前置仓是可判定事实，"范围外"因此能被拒绝也能被测试。
  UNIQUE KEY `uk_mall_courier_scope` (`WAREHOUSE_ID`, `COURIER_ID`),
  INDEX `idx_mall_cscope_courier` (`COURIER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城配送员前置仓服务范围表（E2E-09 S3）';

-- ----------------------------
-- 商城前置仓操作员归属（E2E-09 S3）：让「跨仓操作」成为可判定拒绝
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_warehouse_operator`;
CREATE TABLE `ws_mall_warehouse_operator` (
  `ID`           bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`  tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0生效 1解除',
  `CREATE_BY`    bigint      NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`  varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`    bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`  varchar(14) NOT NULL COMMENT '更新时间',
  `WAREHOUSE_ID` bigint      NOT NULL COMMENT '前置仓ID（ws_mall_warehouse.ID）',
  `OPERATOR_ID`  bigint      NOT NULL COMMENT '管理端操作员ID（api_rbac_employee.ID）',
  PRIMARY KEY (`ID`),
  -- 没有这张表，"仓库人员只能处理归属仓库订单"就只是一句文档：
  -- 拣货/打包/分配只会把 operatorId 写进审计，任何已登录的管理端账号都能动别的仓的货。
  UNIQUE KEY `uk_mall_wh_operator` (`WAREHOUSE_ID`, `OPERATOR_ID`),
  INDEX `idx_mall_wh_operator_op` (`OPERATOR_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城前置仓操作员归属表（E2E-09 S3）';

-- ----------------------------
-- 商城售后主单（E2E-09 S4）：一请求一售后单；退款金额只由服务端按原明细算
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_after_sale`;
CREATE TABLE `ws_mall_after_sale` (
  `ID`                bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`       tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`         bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`       varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`         bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`       varchar(14)  NOT NULL COMMENT '更新时间',
  `AFTER_SALE_NO`     varchar(32)  NOT NULL COMMENT '售后单号：MA+sha256(userId:requestId)前30位大写，由请求确定性派生（禁日期禁序列，重放恒定）',
  `USER_ID`           bigint       NOT NULL COMMENT '申请用户ID；读写恒以会话人过滤，不收前端 userId',
  `REQUEST_ID`        varchar(36)  NOT NULL COMMENT '客户端请求号(UUID)：与 USER_ID 组成申请幂等锚',
  `ORDER_ID`          bigint       NOT NULL COMMENT '原商城订单ID',
  `ORDER_NO`          varchar(32)  NOT NULL COMMENT '原商城订单号快照：三端与售后共用同一编号',
  `WAREHOUSE_ID`      bigint       NOT NULL COMMENT '原履约仓ID快照；仓库操作员归属判定只吃本列，不回查订单',
  `AFTER_SALE_TYPE`   tinyint      NOT NULL COMMENT '售后类型(1400)：1退货退款 2同SKU换货 3未拣货整单取消退款',
  `AFTER_SALE_STATUS` tinyint      NOT NULL COMMENT '售后状态(1399)：1待审核 2待退货 3待质检 4退款处理中 5换货补发中 6已完成 7已驳回 8待人工 9已取消',
  `VERSION`           int          NOT NULL COMMENT '乐观锁版本：创建=1，每次状态转换+1；所有转换 WHERE VERSION=期望值',
  `APPLY_REASON`      varchar(200) NOT NULL COMMENT '申请原因(max200)',
  `REFUND_AMOUNT_FEN` bigint       NOT NULL COMMENT '应退商品金额(分)：服务端按原订单不可变明细 单价×数量 算出，页面与审核人一律不得提交本值',
  `APPLY_TIME`        varchar(14)  NOT NULL COMMENT '申请时间',
  `AUDIT_BY`          bigint       COMMENT '审核人',
  `AUDIT_TIME`        varchar(14)  COMMENT '审核时间',
  `AUDIT_REMARK`      varchar(200) COMMENT '审核备注(max200)',
  `RECEIVE_BY`        bigint       COMMENT '确认收货人',
  `RECEIVE_TIME`      varchar(14)  COMMENT '仓库确认收到退货时间',
  `INSPECT_BY`        bigint       COMMENT '质检人',
  `INSPECT_TIME`      varchar(14)  COMMENT '质检时间',
  `INSPECT_RESULT`    tinyint      COMMENT '质检结论(1401)：1通过可重新销售 2通过不可重新销售 3不通过；只有1才回库',
  `INSPECT_REMARK`    varchar(200) COMMENT '质检说明(max200)：结论必须带原因，不允许页面替运营默认选择',
  `FINISH_TIME`       varchar(14)  COMMENT '售后完成时间',
  `REJECT_REASON`     varchar(200) COMMENT '驳回原因(max200)',
  PRIMARY KEY (`ID`),
  -- 幂等锚与单号唯一键刻意不含 DATA_STATUS：删掉一张售后单不该让同一请求再申请一次
  UNIQUE KEY `uk_mall_as_no` (`AFTER_SALE_NO`),
  UNIQUE KEY `uk_mall_as_user_request` (`USER_ID`, `REQUEST_ID`),
  INDEX `idx_mall_as_order` (`ORDER_ID`),
  INDEX `idx_mall_as_status` (`AFTER_SALE_STATUS`),
  INDEX `idx_mall_as_wh_status` (`WAREHOUSE_ID`, `AFTER_SALE_STATUS`),
  CONSTRAINT `chk_mall_as_refund_nonneg` CHECK (`REFUND_AMOUNT_FEN` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城售后主单表（E2E-09 S4）';

-- ----------------------------
-- 商城售后明细（E2E-09 S4）：退款金额的唯一来源，单价取自原订单不可变快照
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_after_sale_item`;
CREATE TABLE `ws_mall_after_sale_item` (
  `ID`                bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`       tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`         bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`       varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`         bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`       varchar(14)  NOT NULL COMMENT '更新时间',
  `AFTER_SALE_ID`     bigint       NOT NULL COMMENT '售后单ID',
  `ORDER_ITEM_ID`     bigint       NOT NULL COMMENT '原订单明细ID：退款金额的唯一来源',
  `SKU_ID`            bigint       NOT NULL COMMENT 'SKU ID 快照',
  `PRODUCT_NAME`      varchar(100) NOT NULL COMMENT '商品名快照',
  `SKU_NAME`          varchar(100) NOT NULL COMMENT '规格名快照',
  `UNIT_PRICE_FEN`    bigint       NOT NULL COMMENT '单价快照(分)：取自原订单明细，不重新定价',
  `QUANTITY`          int          NOT NULL COMMENT '申请数量(件)',
  `ITEM_AMOUNT_FEN`   bigint       NOT NULL COMMENT '行金额(分)=单价×数量，库层 CHECK 兜底',
  PRIMARY KEY (`ID`),
  -- 一张售后单对同一条原明细只能有一行：否则同一明细可以被拆成两行绕过数量累计上限
  UNIQUE KEY `uk_mall_as_item` (`AFTER_SALE_ID`, `ORDER_ITEM_ID`),
  INDEX `idx_mall_as_item_sku` (`SKU_ID`),
  CONSTRAINT `chk_mall_as_item_qty_positive` CHECK (`QUANTITY` > 0),
  CONSTRAINT `chk_mall_as_item_amount` CHECK (`ITEM_AMOUNT_FEN` = `UNIT_PRICE_FEN` * `QUANTITY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城售后明细表（E2E-09 S4）';

-- ----------------------------
-- 商城售后轨迹（E2E-09 S4）：节点即状态到达史，幂等键防重复写
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_after_sale_trace`;
CREATE TABLE `ws_mall_after_sale_trace` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间',
  `AFTER_SALE_ID`       bigint       NOT NULL COMMENT '售后单ID',
  `AFTER_SALE_NO`       varchar(32)  NOT NULL COMMENT '售后单号快照：三端时间线按本列对齐',
  `TRACE_NODE`          tinyint      NOT NULL COMMENT '到达节点(1399)：与售后状态同值域，轨迹即状态到达史',
  `ACTOR_TYPE`          tinyint      NOT NULL COMMENT '操作方(1397)：1系统 2前置仓 3配送员 4用户',
  `ACTOR_ID`            bigint       NOT NULL COMMENT '操作人ID；系统动作写 0',
  `SUBJECT_ID`          bigint       COMMENT '节点业务对象ID：质检节点记质检结论、换货节点记补发订单ID；与 ACTOR_ID 分列',
  `TRACE_TIME`          varchar(14)  NOT NULL COMMENT '节点时间：与售后单时间、消息同源',
  `TRACE_TEXT`          varchar(200) NOT NULL COMMENT '节点文案(max200)',
  `BIZ_IDEMPOTENCY_KEY` varchar(64)  NOT NULL COMMENT '幂等键：MAT:<afterSaleNo>:<node>。键被占用即证据冲突，整事务回滚',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_as_trace_key` (`BIZ_IDEMPOTENCY_KEY`),
  INDEX `idx_mall_as_trace_no` (`AFTER_SALE_NO`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城售后轨迹表（E2E-09 S4）';

-- ----------------------------
-- 商城退款单（E2E-09 S4）：一售后一退款单，原路退回原支付单
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_refund`;
CREATE TABLE `ws_mall_refund` (
  `ID`                    bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`           tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`             bigint      NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`           varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`             bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`           varchar(14) NOT NULL COMMENT '更新时间',
  `REFUND_NO`             varchar(32) NOT NULL COMMENT '退款单号：MR+sha256(afterSaleNo)前30位大写，由售后单确定性派生',
  `AFTER_SALE_ID`         bigint      NOT NULL COMMENT '售后单ID',
  `AFTER_SALE_NO`         varchar(32) NOT NULL COMMENT '售后单号快照',
  `ORDER_ID`              bigint      NOT NULL COMMENT '原商城订单ID',
  `ORDER_NO`              varchar(32) NOT NULL COMMENT '原商城订单号快照',
  `PAYMENT_ID`            bigint      NOT NULL COMMENT '原商城支付单ID：退款只能原路退回本单',
  `USER_ID`               bigint      NOT NULL COMMENT '收款用户ID',
  `REFUND_AMOUNT_FEN`     bigint      NOT NULL COMMENT '退款金额(分)：来自售后单，页面不得提交',
  `CURRENCY`              varchar(16) NOT NULL COMMENT '币种：与原支付单一致，金额相同币种不同等于退错了钱',
  `REFUND_STATUS`         tinyint     NOT NULL COMMENT '退款状态(1402)：1待退款 2退款成功 3退款失败 4已关闭',
  `REFUND_SOURCE`         tinyint     NOT NULL COMMENT '退款来源：与原支付来源一致（1微信 2Pay-Sim）',
  `REFUND_TRANSACTION_ID` varchar(64) COMMENT '渠道退款交易号；待退款时为空',
  `REFUND_SUCCESS_TIME`   varchar(14) COMMENT '渠道退款成功时间：取自渠道事实而非本地时钟',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_refund_no` (`REFUND_NO`),
  -- 一张售后单只允许一张退款单：否则同一次售后能退两遍钱
  UNIQUE KEY `uk_mall_refund_after_sale` (`AFTER_SALE_ID`),
  -- 同一笔渠道退款交易不得记到两张退款单
  UNIQUE KEY `uk_mall_refund_transaction` (`REFUND_TRANSACTION_ID`),
  INDEX `idx_mall_refund_order` (`ORDER_ID`),
  CONSTRAINT `chk_mall_refund_amount_positive` CHECK (`REFUND_AMOUNT_FEN` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城退款单表（E2E-09 S4）';

-- ----------------------------
-- 商城退款事实收件箱（E2E-09 S4）：渠道事实只留证，不直接改订单/库存/售后
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_refund_fact`;
CREATE TABLE `ws_mall_refund_fact` (
  `ID`                    bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`           tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`             bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`           varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`             bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`           varchar(14)  NOT NULL COMMENT '更新时间',
  `REFUND_SOURCE`         tinyint      NOT NULL COMMENT '退款来源（1微信 2Refund-Sim）',
  `FACT_CHANNEL`          tinyint      NOT NULL COMMENT '事实渠道：1退款通知 2主动查单 3Refund-Sim',
  `PROVIDER_EVENT_KEY`    varchar(100) NOT NULL COMMENT '渠道事件键：与来源、渠道共同唯一，同一外部事件只收一次',
  `REFUND_ID`             bigint       COMMENT '商城退款单ID（可空：事实先到而退款单未建时留证转人工）',
  `AFTER_SALE_ID`         bigint       COMMENT '售后单ID',
  `ORDER_NO`              varchar(32)  NOT NULL COMMENT '原商城订单号',
  `REFUND_NO`             varchar(32)  NOT NULL COMMENT '退款单号',
  `REFUND_STATE`          varchar(32)  NOT NULL COMMENT '规范化退款状态：SUCCESS/PROCESSING/CLOSED；其他一律留证转人工，不做语义猜测',
  `REFUND_TRANSACTION_ID` varchar(64)  COMMENT '渠道退款交易号',
  `REFUND_AMOUNT_FEN`     bigint       COMMENT '渠道回报退款金额(分)',
  `CURRENCY`              varchar(16)  COMMENT '币种',
  `REFUND_SUCCESS_TIME`   varchar(14)  COMMENT '渠道退款成功时间（14位业务时间）',
  `RAW_BODY`              mediumtext   COMMENT '原始报文：只用于核验、重放与审计，不进任何展示出口',
  `RAW_BODY_SHA256`       char(64)     NOT NULL COMMENT '原始报文SHA-256：同键重放的正文一致性锚',
  `VERIFY_METHOD`         tinyint      NOT NULL COMMENT '校验方式：1渠道验签 2主动查单 3Refund-Sim内部',
  `PROCESSING_STATUS`     tinyint      NOT NULL COMMENT '处理状态(1403)：1待处理 2处理中 3已处理 4待重试 5需对账',
  `RETRY_COUNT`           int          NOT NULL DEFAULT 0 COMMENT '重试次数：到上限转人工，绝不无限循环',
  `NEXT_RETRY_TIME`       varchar(14)  COMMENT '下次重试时间',
  `CLAIM_TIME`            varchar(14)  COMMENT '认领时间',
  `LEASE_UNTIL`           varchar(14)  COMMENT '租约到期：到期可被重新认领，进程崩溃不会让事实永久卡在处理中',
  `LAST_ERROR`            varchar(500) COMMENT '最近失败原因',
  `RECEIVED_TIME`         varchar(14)  NOT NULL COMMENT '收单时间',
  `PROCESSED_TIME`        varchar(14)  COMMENT '处理完成时间',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_refund_fact_key` (`REFUND_SOURCE`, `FACT_CHANNEL`, `PROVIDER_EVENT_KEY`),
  INDEX `idx_mall_refund_fact_status` (`PROCESSING_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城退款事实收件箱表（E2E-09 S4）';

-- ----------------------------
-- 商城出库包裹表（E2E-09 L1 多渠道物流）
-- 一个履约总单可挂多个包裹；一期只产生一个正向包裹，多包裹能力先建模不实现，
-- 免得真要做部分发货时再改一次已上线的唯一键。
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_shipment`;
CREATE TABLE `ws_mall_shipment` (
  `ID`                    bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`           tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`             bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`           varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`             bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`           varchar(14)  NOT NULL COMMENT '更新时间',
  `FULFILL_ID`            bigint       NOT NULL COMMENT '所属履约总单ID（ws_mall_fulfillment.ID）',
  `ORDER_ID`              bigint       NOT NULL COMMENT '商城订单ID（冗余自履约总单，供按单直查）',
  `ORDER_NO`              varchar(32)  NOT NULL COMMENT '商城订单号：三端共用的唯一业务号',
  `SOURCE_AFTER_SALE_ID`  bigint       COMMENT '来源售后单ID：换货补发/退货包裹的来源，正向发货恒 NULL',
  `DIRECTION`             tinyint      NOT NULL COMMENT '包裹方向(1405)：1正向发货 2退货 3换货补发',
  `SHIPMENT_SEQ`          int          NOT NULL DEFAULT 1 COMMENT '同方向内的包裹序号，从1起；一期恒为1',
  `FULFILL_MODE`          tinyint      NOT NULL COMMENT '承运渠道(1404)：1自营配送 2第三方物流；随履约总单冻结值',
  `PROVIDER_CODE`         varchar(32)  COMMENT '承运商编码(max32)：自营恒 SELF；第三方为适配器编码，如 SIM',
  `SERVICE_CODE`          varchar(32)  COMMENT '服务类型编码(max32)：如次日达/标快，由适配器定义，平台不解释',
  `PROVIDER_ORDER_NO`     varchar(64)  COMMENT '承运方订单号(max64)：适配器返回，未取得前为空',
  `WAYBILL_NO`            varchar(64)  COMMENT '运单号(max64)：适配器返回，未取得前为空',
  `SHIPMENT_STATUS`       tinyint      NOT NULL COMMENT '包裹状态(1406)：1待发运 2已受理 3已揽收 4运输中 5已送达 6已签收 7已取消 8异常待人工',
  `VERSION`               int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本：状态推进恒用「精确前态+版本」CAS',
  `CREATE_SHIP_TIME`      varchar(14)  COMMENT '包裹创建时间（业务时间）',
  `PICKUP_TIME`           varchar(14)  COMMENT '揽收时间',
  `DELIVER_TIME`          varchar(14)  COMMENT '送达时间',
  `CANCEL_TIME`           varchar(14)  COMMENT '取消时间',
  `BIZ_IDEMPOTENCY_KEY`   varchar(80)  NOT NULL COMMENT '包裹幂等键：MSHIP:<orderNo>:<direction>:<seq>，确定性派生，重复创建撞键',
  PRIMARY KEY (`ID`),
  -- 三把唯一键都不含 DATA_STATUS：逻辑删不放开占位（与本仓其它唯一键同口径）
  UNIQUE INDEX `uk_mall_ship_key` (`BIZ_IDEMPOTENCY_KEY`),
  UNIQUE INDEX `uk_mall_ship_seq` (`FULFILL_ID`, `DIRECTION`, `SHIPMENT_SEQ`),
  -- 同一承运商下运单号唯一：两个包裹共用一个运单号意味着其中一个的物流事件会被记到另一个头上
  UNIQUE INDEX `uk_mall_ship_waybill` (`PROVIDER_CODE`, `WAYBILL_NO`),
  INDEX `idx_mall_ship_order` (`ORDER_NO`),
  INDEX `idx_mall_ship_status` (`SHIPMENT_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城出库包裹表';

-- ----------------------------
-- 商城出库包裹明细表（E2E-09 L1）
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_shipment_item`;
CREATE TABLE `ws_mall_shipment_item` (
  `ID`                  bigint  NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`           bigint  NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`           bigint  NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14) NOT NULL COMMENT '更新时间',
  `SHIPMENT_ID`         bigint  NOT NULL COMMENT '所属包裹ID（ws_mall_shipment.ID）',
  `ORDER_ITEM_ID`       bigint  NOT NULL COMMENT '原订单明细ID（ws_mall_order_item.ID）',
  `AFTER_SALE_ITEM_ID`  bigint  NOT NULL DEFAULT 0 COMMENT '售后明细ID（ws_mall_after_sale_item.ID）；正向发货恒 0，不用 NULL 是为了让唯一键可判定',
  `SKU_ID`              bigint  NOT NULL COMMENT 'SKU ID（冗余快照，便于按 SKU 直查）',
  `QUANTITY`            int     NOT NULL COMMENT '本包裹内该明细的件数，恒为正',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_mall_ship_item` (`SHIPMENT_ID`, `ORDER_ITEM_ID`, `AFTER_SALE_ITEM_ID`),
  INDEX `idx_mall_ship_item_ship` (`SHIPMENT_ID`),
  CONSTRAINT `chk_mall_ship_item_qty` CHECK (`QUANTITY` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城出库包裹明细表';

-- ----------------------------
-- 商城物流事件收件箱（E2E-09 L1）
-- 与支付/退款事实同形：外部事实先落库留证，推进交给独立事务；
-- 回调恒「先验签、再去重、再落事实、最后才推进状态」。
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_logistics_event`;
CREATE TABLE `ws_mall_logistics_event` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间',
  `PROVIDER_CODE`       varchar(32)  NOT NULL COMMENT '承运商编码(max32)',
  `FACT_CHANNEL`        tinyint      NOT NULL COMMENT '事实渠道：1回调推送 2主动查询 3内部模拟',
  `PROVIDER_EVENT_KEY`  varchar(128) NOT NULL COMMENT '承运方事件唯一键(max128)：同键即同一条事实，重放复用原行',
  `SHIPMENT_ID`         bigint       COMMENT '归属包裹ID；运单号对不上任何包裹时留空并转人工',
  `WAYBILL_NO`          varchar(64)  NOT NULL COMMENT '运单号(max64)：事实自带，与包裹核对',
  `EVENT_STATE`         varchar(32)  NOT NULL COMMENT '事件状态(1407)：CREATED/PICKED_UP/IN_TRANSIT/DELIVERED/SIGNED/EXCEPTION/CANCELLED；白名单外留证转人工',
  `EVENT_TIME`          varchar(14)  NOT NULL COMMENT '承运方事件发生时间（14位业务时间，严格校验）',
  `EVENT_DESC`          varchar(200) COMMENT '事件描述(max200)：承运方文案，平台只展示不解析',
  `RAW_BODY`            text         COMMENT '原始报文：留证用，不下发任何前端',
  `RAW_BODY_SHA256`     char(64)     NOT NULL COMMENT '原始报文摘要：同键重放的正文一致性判据',
  `VERIFY_METHOD`       tinyint      NOT NULL COMMENT '验签方式：1内部模拟签名 2承运方签名（真实厂商接入后启用）',
  `PROCESSING_STATUS`   tinyint      NOT NULL COMMENT '处理状态(1408)：1待处理 2处理中 3已处理 4待重试 5需人工',
  `RETRY_COUNT`         int          NOT NULL DEFAULT 0 COMMENT '重试次数：到上限转人工，绝不无限循环',
  `RECEIVED_TIME`       varchar(14)  NOT NULL COMMENT '事实接收时间',
  `PROCESSED_TIME`      varchar(14)  COMMENT '处理完成时间',
  `CLAIM_TIME`          varchar(14)  COMMENT '认领时间',
  `LEASE_UNTIL`         varchar(14)  COMMENT '租约到期：租约过期后其他处理者可重新认领，防止崩溃后卡死',
  `LAST_ERROR`          varchar(500) COMMENT '最近一次失败原因(max500)',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_mall_logi_event_key` (`PROVIDER_CODE`, `FACT_CHANNEL`, `PROVIDER_EVENT_KEY`),
  INDEX `idx_mall_logi_event_waybill` (`WAYBILL_NO`),
  INDEX `idx_mall_logi_event_status` (`PROCESSING_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城物流事件收件箱';

-- ----------------------------
-- 商城物流出站动作表（E2E-09 L1）
-- 外部请求不得发生在数据库事务内：业务事务只写本表，Worker 负责调适配器。
-- ----------------------------
DROP TABLE IF EXISTS `ws_mall_logistics_outbox`;
CREATE TABLE `ws_mall_logistics_outbox` (
  `ID`                bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`       tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`         bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`       varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`         bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`       varchar(14)  NOT NULL COMMENT '更新时间',
  `SHIPMENT_ID`       bigint       NOT NULL COMMENT '目标包裹ID（ws_mall_shipment.ID）',
  `ACTION_TYPE`       tinyint      NOT NULL COMMENT '动作类型(1409)：1创建运单 2取消运单',
  `BIZ_ACTION_KEY`    varchar(80)  NOT NULL COMMENT '动作幂等键：MLOG:<shipmentId>:<actionType>，同动作只登记一次',
  `REQUEST_SNAP`      text         NOT NULL COMMENT '请求快照：登记时冻结的入参，Worker 只按快照调用，不回读当前业务态',
  `PROCESSING_STATUS` tinyint      NOT NULL COMMENT '处理状态(1408)：1待处理 2处理中 3已处理 4待重试 5需人工',
  `RETRY_COUNT`       int          NOT NULL DEFAULT 0 COMMENT '重试次数：到上限转人工',
  `NEXT_RETRY_TIME`   varchar(14)  COMMENT '下次重试时间：退避后才再取',
  `CLAIM_TIME`        varchar(14)  COMMENT '认领时间',
  `LEASE_UNTIL`       varchar(14)  COMMENT '租约到期',
  `LAST_ERROR`        varchar(500) COMMENT '最近一次失败原因(max500)',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_mall_logi_outbox_key` (`BIZ_ACTION_KEY`),
  INDEX `idx_mall_logi_outbox_status` (`PROCESSING_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城物流出站动作表';

-- 微信发货管理同步 outbox（WX-ECO S4）
DROP TABLE IF EXISTS `ws_wechat_shipping_outbox`;
CREATE TABLE `ws_wechat_shipping_outbox` (
  `ID`                bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`       tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`         bigint       NOT NULL COMMENT '创建人ID（业务事务登记，系统恒0）',
  `CREATE_TIME`       varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`         bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`       varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `ORDER_ID`          bigint       NOT NULL COMMENT '商城订单ID（ws_mall_order.ID）',
  `ORDER_NO`          varchar(32)  NOT NULL COMMENT '商城订单号(out_trade_no)：与微信侧共键之一',
  `SHIPMENT_ID`       bigint       NOT NULL COMMENT '包裹ID（ws_mall_shipment.ID）：事实源，本表不复制包裹状态',
  `TRANSACTION_ID`    varchar(64)  NOT NULL COMMENT '微信支付交易号：upload_shipping_info 的定位共键；取自 ws_mall_payment，Worker 发送前复核仍为微信来源',
  `RECEIVER_USER_ID`  bigint       NOT NULL COMMENT '付款人(ws_user.ID)；payer openid 由 Worker 发送时按 ID 现查，绝不落库',
  `BIZ_SYNC_KEY`      varchar(100) NOT NULL COMMENT '幂等键 WXSHIP:<orderNo>:<direction>:<seq>；同一包裹恒一行',
  `LOGISTICS_TYPE`    tinyint      NOT NULL COMMENT '微信物流模式：1实体快递 2同城配送 3虚拟商品 4用户自提；自营配送=2，第三方=1',
  `PROVIDER_CODE`     varchar(32)  DEFAULT NULL COMMENT '承运商编码：LOGISTICS_TYPE=1 必填（微信 delivery_id 由适配器映射）',
  `WAYBILL_NO`        varchar(64)  DEFAULT NULL COMMENT '运单号：LOGISTICS_TYPE=1 必填',
  `ITEM_DESC`         varchar(120) NOT NULL COMMENT '商品描述（微信必填，用户在微信侧可见）',
  `PAYLOAD_SNAP`      text         COMMENT '同步报文快照（业务事务内冻结）：Worker 不反查业务表',
  `PROCESSING_STATUS` tinyint      NOT NULL COMMENT '处理状态(1408，与通知/物流 outbox 共用值域)：1待处理 2处理中 3已处理 4待重试 5需人工',
  `RETRY_COUNT`       int          NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `NEXT_RETRY_TIME`   varchar(14)  DEFAULT NULL COMMENT '下次可重试时间（退避）',
  `CLAIM_TIME`        varchar(14)  DEFAULT NULL COMMENT '认领时间',
  `LEASE_UNTIL`       varchar(14)  DEFAULT NULL COMMENT '租约到期；过期可被重新认领，防进程崩溃后永久卡在处理中',
  `SKIP_REASON`       varchar(40)  DEFAULT NULL COMMENT '未同步原因：NOT_WECHAT_PAY非微信收款单 / CLIENT_UNCONFIGURED适配器未接。已处理但没同步，与同步成功必须可区分',
  `LAST_ERROR`        varchar(500) DEFAULT NULL COMMENT '最近一次失败原因',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_wxship_key` (`BIZ_SYNC_KEY`),
  KEY `idx_wxship_status` (`PROCESSING_STATUS`, `NEXT_RETRY_TIME`),
  KEY `idx_wxship_order` (`ORDER_NO`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='微信发货管理同步出站队列：业务事务只登记，Worker 提交后外呼；Pay-Sim 单落 SKIP 不外呼';



-- ============================================================
-- E2E-09 商城 S4：退货退款、库存回库与同 SKU 换货补发（既有库增量迁移）
--
-- 依赖：本库已执行 mall-s1 / mall-s2 / mall-s3 三份迁移。
-- 与 server/sql/ws_mall.sql（领域源，仅限空库）和 deploy/mysql/init/02-ws-business.sql
-- （空库初始化）三轨逐字一致；测试轨见 MallDbSchema.java。
--
-- 幂等：建表用 CREATE TABLE IF NOT EXISTS；加列与加索引用 information_schema 探测 + PREPARE；
-- 字典用 NOT EXISTS（api_dict_* 除主键外无唯一键，IGNORE 无键可撞会翻倍）；
-- 菜单与角色绑定携显式 ID 用 INSERT IGNORE。全文无 DROP/TRUNCATE/无 WHERE 的 DELETE 或 UPDATE。
--
-- 换货补发刻意不新建第二套履约状态机：补发走一张 ORDER_AMOUNT_FEN=0 的内部订单，
-- 由 SOURCE_AFTER_SALE_ID 标识来源并复用 S3 的七态履约链。该列唯一键保证一张售后单
-- 只补发一次；普通订单该列恒 NULL，MySQL 唯一索引允许多个 NULL，故一期语义零影响。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ws_mall_after_sale` (
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

CREATE TABLE IF NOT EXISTS `ws_mall_after_sale_item` (
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

CREATE TABLE IF NOT EXISTS `ws_mall_after_sale_trace` (
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

CREATE TABLE IF NOT EXISTS `ws_mall_refund` (
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

CREATE TABLE IF NOT EXISTS `ws_mall_refund_fact` (
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
-- 6) 商城订单加换货补发来源列与唯一键（幂等：先探测再 ALTER）
-- ----------------------------
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ws_mall_order'
               AND column_name = 'SOURCE_AFTER_SALE_ID');
SET @sql := IF(@col = 0,
  'ALTER TABLE `ws_mall_order` ADD COLUMN `SOURCE_AFTER_SALE_ID` bigint NULL COMMENT ''换货补发来源售后单ID：非空即内部零价补发单，不计新销售收入；普通订单恒 NULL'' AFTER `RECEIVER_DISTRICT_CODE`',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'ws_mall_order'
               AND index_name = 'uk_mall_order_source_after_sale');
SET @sql := IF(@idx = 0,
  'ALTER TABLE `ws_mall_order` ADD UNIQUE KEY `uk_mall_order_source_after_sale` (`SOURCE_AFTER_SALE_ID`)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------
-- 7) 字典：1391 增值 9~11（换货三动作）；新增 1399~1403
--    换货库存动作刻意不复用人工入库/出库（1~4）：那是 PC 人工端点的准入口，
--    混用会让换货动作可以从人工端点手工制造，也让审计分不清是谁动的库存。
-- ----------------------------
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.DICT_NAME, s.DICT_TYPE, s.DICT_REMARK FROM (
            SELECT '商城售后状态' AS DICT_NAME, '1399' AS DICT_TYPE, '商城退货退款与换货状态机' AS DICT_REMARK
  UNION ALL SELECT '商城售后类型', '1400', '商城售后申请类型'
  UNION ALL SELECT '商城质检结论', '1401', '商城退货质检结论'
  UNION ALL SELECT '商城退款状态', '1402', '商城退款单状态'
  UNION ALL SELECT '商城退款事实处理状态', '1403', '商城退款事实收件箱处理状态'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1391' AS DICT_TYPE, 9 AS DICT_SORT, 9 AS DICT_VALUE, '换货预占' AS DICT_LABEL
  UNION ALL SELECT '1391', 10, 10, '换货出库'
  UNION ALL SELECT '1391', 11, 11, '换货释放'
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
-- 8) 菜单：商城售后（1045 为实测空号，编号区间按模块交错分布，「上一个 +1」推不出空号）
-- ----------------------------
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`)
VALUES
(1045,'商城售后',2,NULL,1005,7,'aftersale','/mall/aftersale',1,NULL,NULL,1,1,0,1,'20260810120000',1,'20260810120000');

INSERT IGNORE INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260810120000',1,'20260810120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` IN (1045)
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

-- ----------------------------
-- 后置不变式：五表、来源列与唯一键、字典与菜单就位
-- 售后表刻意不带演示种子：没有真实订单与支付事实的假售后单会让订单、资金、库存互相打架。
-- ----------------------------
SELECT
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
    AND table_name IN ('ws_mall_after_sale','ws_mall_after_sale_item','ws_mall_after_sale_trace',
                       'ws_mall_refund','ws_mall_refund_fact')) AS s4_tables_expect_5,
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'ws_mall_order' AND column_name = 'SOURCE_AFTER_SALE_ID') AS source_col_expect_1,
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'ws_mall_order' AND index_name = 'uk_mall_order_source_after_sale') AS source_uk_expect_1,
  (SELECT COUNT(*) FROM api_dict_type WHERE DICT_TYPE IN ('1399','1400','1401','1402','1403')) AS dict_types_expect_5,
  (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE = '1391') AS flow_type_values_expect_11,
  (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE IN ('1399','1400','1401','1402','1403')) AS dict_values_expect_24,
  (SELECT COUNT(*) FROM api_rbac_menu WHERE MENU_COMPONENT = '/mall/aftersale') AS menu_expect_1;

SELECT '2026-08-10 迁移完成：E2E-09 S4 售后五表、换货来源列、字典 1391#9~11 与 1399~1403、商城售后菜单就绪' AS RESULT;

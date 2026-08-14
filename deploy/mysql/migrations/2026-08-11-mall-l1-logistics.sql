-- ============================================================
-- 2026-08-11 · E2E-09 L1 多渠道物流履约基座
--
-- 本迁移做四件事，全部幂等、无破坏 DDL（无 DROP/TRUNCATE/无 WHERE 的 DELETE/UPDATE）：
--   1) ws_mall_fulfillment 增 FULFILL_MODE（履约渠道），历史行回填为 1 自营配送
--   2) 字典 1396 的 3~6 标签改渠道中立（值不变），并新增字典 1404~1409
--   3) 新建四张物流表
--   4) 历史自营履约任务确定性回填 shipment / shipment_item
--
-- 为什么历史行回填 1 而不是 0：0 的语义是「还没人决定走哪条链」，而这些任务的货
-- 早已由自营配送员送完。填 0 会让它们在 PC 上重新出现「请选择承运渠道」，
-- 也会让并发冻结判据认为它们仍可被抢占。
--
-- 回填的幂等锚是确定性派生的 BIZ_IDEMPOTENCY_KEY（MSHIP:<orderNo>:1:1），
-- 重复执行撞唯一键即跳过，零重复。
-- ============================================================
SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1) FULFILL_MODE 列
-- ------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ws_mall_fulfillment'
    AND COLUMN_NAME = 'FULFILL_MODE');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE `ws_mall_fulfillment` ADD COLUMN `FULFILL_MODE` tinyint NOT NULL DEFAULT 0
     COMMENT ''履约渠道(1404)：0未确定 1自营配送 2第三方物流；由分配配送员或创建运单 CAS 冻结，冻结后不可改''
     AFTER `FULFILL_STATUS`',
  'SELECT ''FULFILL_MODE 已存在，跳过'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史行回填：判据是「已经分配过配送员」，不是「值还是 0」。
--
-- 只按 FULFILL_MODE = 0 回填有两个错：
-- ① 迁移执行后系统继续运行，新支付的订单会生成 FULFILL_MODE = 0 的任务（渠道本就未定）。
--    运维重跑一次本迁移，这些单会被误冻结成自营，此后再也不能选第三方；
-- ② 即使首次执行，它也把还停在待拣货/待打包/待安排发运、从没分配过配送员的历史任务
--    一并锁成自营——那些单在 L1 之后本该可以走第三方。
--
-- COURIER_ID IS NOT NULL 同时闭合这两点：有配送员必然是自营（L1 之前只有这一条链），
-- 没有配送员的任务无论新旧都保持 0 未定，交给运营在 PC 上选。
-- 重跑安全：新任务一旦分配了自营配送员，其 FULFILL_MODE 已由 CAS 冻结成 1，
-- WHERE FULFILL_MODE = 0 不再成立。
UPDATE `ws_mall_fulfillment` SET `FULFILL_MODE` = 1
 WHERE `FULFILL_MODE` = 0 AND `COURIER_ID` IS NOT NULL;

-- 状态列注释同步渠道中立口径（注释变更不影响数据）
ALTER TABLE `ws_mall_fulfillment` MODIFY COLUMN `FULFILL_STATUS` tinyint NOT NULL
  COMMENT '履约状态(1396)：1待拣货 2待打包 3待安排发运 4待承运方揽收 5运输中 6已送达待确认 7已签收（渠道中立，自营与第三方共用）';

-- ------------------------------------------------------------
-- 2) 字典：1396 标签改渠道中立（值不变）+ 新增 1404~1409
--    带 WHERE 的定向 UPDATE，不是无条件刷全表
-- ------------------------------------------------------------
UPDATE `api_dict_data` SET `DICT_LABEL` = '待安排发运'   WHERE `DICT_TYPE` = '1396' AND `DICT_VALUE` = 3;
UPDATE `api_dict_data` SET `DICT_LABEL` = '待承运方揽收' WHERE `DICT_TYPE` = '1396' AND `DICT_VALUE` = 4;
UPDATE `api_dict_data` SET `DICT_LABEL` = '运输中'       WHERE `DICT_TYPE` = '1396' AND `DICT_VALUE` = 5;
UPDATE `api_dict_data` SET `DICT_LABEL` = '已送达待确认' WHERE `DICT_TYPE` = '1396' AND `DICT_VALUE` = 6;


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



-- ------------------------------------------------------------
-- 3) 四张物流表
-- ------------------------------------------------------------

-- ----------------------------
-- 商城出库包裹表（E2E-09 L1 多渠道物流）
-- 一个履约总单可挂多个包裹；一期只产生一个正向包裹，多包裹能力先建模不实现，
-- 免得真要做部分发货时再改一次已上线的唯一键。
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_shipment` (
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
CREATE TABLE IF NOT EXISTS `ws_mall_shipment_item` (
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
CREATE TABLE IF NOT EXISTS `ws_mall_logistics_event` (
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
CREATE TABLE IF NOT EXISTS `ws_mall_logistics_outbox` (
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



-- ------------------------------------------------------------
-- 4) 历史自营履约任务回填 shipment / shipment_item
--
-- 每张既有履约任务派生恰一个正向包裹；包裹状态按履约状态确定性映射，
-- 不猜、不取「当前时间」——回填出来的时间必须来自任务自己的时间列。
-- ------------------------------------------------------------
INSERT INTO `ws_mall_shipment`
(`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,
 `FULFILL_ID`,`ORDER_ID`,`ORDER_NO`,`SOURCE_AFTER_SALE_ID`,`DIRECTION`,`SHIPMENT_SEQ`,
 `FULFILL_MODE`,`PROVIDER_CODE`,`SERVICE_CODE`,`PROVIDER_ORDER_NO`,`WAYBILL_NO`,
 `SHIPMENT_STATUS`,`VERSION`,`CREATE_SHIP_TIME`,`PICKUP_TIME`,`DELIVER_TIME`,`CANCEL_TIME`,
 `BIZ_IDEMPOTENCY_KEY`)
SELECT 0, 1, f.`CREATE_TIME`, 1, f.`UPDATE_TIME`,
       f.`ID`, f.`ORDER_ID`, f.`ORDER_NO`, NULL, 1, 1,
       1, 'SELF', NULL, NULL, NULL,
       CASE
         WHEN f.`FULFILL_STATUS` IN (1, 2, 3) THEN 1  -- 待发运
         WHEN f.`FULFILL_STATUS` = 4 THEN 2           -- 已受理（已分配配送员）
         WHEN f.`FULFILL_STATUS` = 5 THEN 4           -- 运输中
         WHEN f.`FULFILL_STATUS` = 6 THEN 5           -- 已送达
         ELSE 6                                        -- 已签收
       END,
       1, f.`CREATE_TIME`, f.`FETCH_TIME`, f.`ARRIVE_TIME`, NULL,
       CONCAT('MSHIP:', f.`ORDER_NO`, ':1:1')
FROM `ws_mall_fulfillment` f
-- 与上面的渠道回填同一判据：只给「确实由自营配送员送过」的历史任务补包裹。
-- 少了这个条件，重跑本迁移会给迁移之后新建的、渠道未定的任务凭空造出一条
-- PROVIDER_CODE='SELF' 的包裹——那是一件业务从未产生过的出库物，
-- 而它随后会被三端当成真包裹展示，也会被共键校验器当成真证据。
WHERE f.`FULFILL_MODE` = 1 AND f.`COURIER_ID` IS NOT NULL
  AND NOT EXISTS (
  SELECT 1 FROM `ws_mall_shipment` s
  WHERE s.`BIZ_IDEMPOTENCY_KEY` = CONCAT('MSHIP:', f.`ORDER_NO`, ':1:1'));

INSERT INTO `ws_mall_shipment_item`
(`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,
 `SHIPMENT_ID`,`ORDER_ITEM_ID`,`AFTER_SALE_ITEM_ID`,`SKU_ID`,`QUANTITY`)
SELECT 0, 1, s.`CREATE_TIME`, 1, s.`UPDATE_TIME`,
       s.`ID`, i.`ID`, 0, i.`SKU_ID`, i.`QUANTITY`
FROM `ws_mall_shipment` s
JOIN `ws_mall_order_item` i ON i.`ORDER_ID` = s.`ORDER_ID`
WHERE s.`DIRECTION` = 1 AND s.`SHIPMENT_SEQ` = 1
  AND NOT EXISTS (
    SELECT 1 FROM `ws_mall_shipment_item` t
    WHERE t.`SHIPMENT_ID` = s.`ID` AND t.`ORDER_ITEM_ID` = i.`ID` AND t.`AFTER_SALE_ITEM_ID` = 0);

-- ------------------------------------------------------------
-- 5) 后置不变式
-- ------------------------------------------------------------
SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ws_mall_fulfillment'
      AND COLUMN_NAME = 'FULFILL_MODE') AS mode_col_expect_1,
  -- 渠道未定的任务数**不再期望 0**：没有配送员的历史任务与迁移后新建的任务都该保持 0，
  -- 由运营在 PC 上选渠道。期望 0 是回填口径收窄之前的旧断言，留着会在主库上误判成失败。
  (SELECT COUNT(*) FROM `ws_mall_fulfillment` WHERE `FULFILL_MODE` = 0) AS undecided_no_courier,
  -- 真正的不变量：已冻结自营的任务必须恰好都有配送员，一个都不能多
  (SELECT COUNT(*) FROM `ws_mall_fulfillment`
    WHERE `FULFILL_MODE` = 1 AND `COURIER_ID` IS NULL) AS self_without_courier_expect_0,
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('ws_mall_shipment','ws_mall_shipment_item',
                         'ws_mall_logistics_event','ws_mall_logistics_outbox')) AS tables_expect_4,
  (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE IN ('1404','1405','1406','1407','1408','1409')) AS dict_rows_expect_28,
  (SELECT COUNT(*) FROM `ws_mall_fulfillment` WHERE `COURIER_ID` IS NOT NULL) AS self_delivered_total,
  -- 正向包裹数应等于「已分配配送员的任务数」而不是任务总数：回填口径收窄后，
  -- 没有配送员的任务本就不该有包裹（第三方单的包裹由 createShipment 建，不由本迁移建）
  (SELECT COUNT(*) FROM `ws_mall_shipment`
    WHERE DIRECTION = 1 AND PROVIDER_CODE = 'SELF') AS self_forward_shipments_must_equal_left,
  (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE = '1396' AND DICT_VALUE = 4 AND DICT_LABEL = '待承运方揽收') AS relabel_expect_1;

SELECT '2026-08-11 迁移完成：L1 多渠道物流基座（FULFILL_MODE + 四表 + 字典 1404~1409 + 历史回填）' AS result;

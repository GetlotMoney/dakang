-- ============================================================
-- 微信交易订单发货管理同步 outbox（WX-ECO S4，2026-08-13）
--
-- 微信支付的商城单发货后，须经 upload_shipping_info 向微信同步发货信息，
-- 否则资金不结算、用户在微信侧看不到发货。事实源是既有 ws_mall_shipment
-- （E2E-09 包裹事实），本表只登记「哪个包裹待同步」，不复制包裹状态机。
--
-- 【为什么另建表而不复用 ws_wechat_notify_outbox】同步对象与失败处置不同：
-- 订阅通知发给用户、失败多因未授权；发货同步发给微信平台、失败多因凭据或
-- 交易号不符，且只发生在微信真实收款单上（Pay-Sim 单落 SKIP 不外呼）。
-- 混表会让运维筛「需人工」得到两类根本不同的待办。
--
-- 状态字典沿用 1408 五态（与通知/物流 outbox 共用值域），不新铸口径。
-- 幂等：CREATE TABLE IF NOT EXISTS；重复执行零变更。非破坏：仅建表。
-- ============================================================

SET NAMES utf8mb4;

-- 前置守卫：存量同名表若缺幂等唯一键，"同一包裹只同步一次"没有地基
SET @tbl := (SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name = 'ws_wechat_shipping_outbox');
SET @uk := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ws_wechat_shipping_outbox'
              AND index_name = 'uk_wxship_key' AND non_unique = 0);
SET @sql := IF(@tbl = 0 OR @uk > 0, 'SELECT 1',
  'SELECT `中止：存量 ws_wechat_shipping_outbox 缺 uk_wxship_key 唯一键`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

CREATE TABLE IF NOT EXISTS `ws_wechat_shipping_outbox` (
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

-- 不插任何种子：待同步包裹是运行时事实。

-- 终检
SET @final := (SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'ws_wechat_shipping_outbox')
            + (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ws_wechat_shipping_outbox'
                 AND index_name = 'uk_wxship_key' AND non_unique = 0);
SET @sql := IF(@final = 2, 'SELECT ''微信发货同步 outbox 迁移完成''', 'SELECT `中止：终检失败`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

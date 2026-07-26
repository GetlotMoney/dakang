-- ============================================================
-- L2-DB 迁移（充值链 schema：支付单唯一键 + 支付事件收件箱 + 业务幂等键）
-- 契约依据：docs/contracts/L2-recharge-contract-v2.md §5.1 / §5.2 / §5.3
-- 仅在独立临时 MySQL 验证，禁止执行主数据库。
-- 执行前备份：mysqldump -uroot -p dakang > backup-$(date +%F).sql
-- 用法：docker exec -i <容器> mysql --default-character-set=utf8mb4 -uroot -p'<密码>' <库> < 本文件
--
-- 结构（两阶段，硬性）：
--   阶段一 全部只读校验（纯 SELECT）→ 任一不通过即以错误终止，此时**未执行任何 UPDATE/ALTER**；
--   阶段二 才做结构变更，且每一步都按 information_schema 判断后再执行（幂等，重复执行零报错）。
--
-- 关于"不残留存储过程"（修掉 L2-AUTH 遗留 P2）：
--   本脚本**完全不使用存储过程**。校验用用户变量累积，中止用动态 SQL 触发一个自带中文原因的
--   列名未知错误；因此不存在"失败时尾部 DROP PROCEDURE 未执行 → 库里残留过程"的路径，
--   information_schema.routines 在任何退出路径下都保持不变。
--
-- 关于索引结构校验（修掉 L2-AUTH 遗留 P2）：
--   同名索引不仅校验唯一性与列名/列序，**还校验 SUB_PART IS NULL**，
--   使"同名前缀唯一索引（如 ORDER_NO(10)）"不会被误判合格而跳过建索引。
-- ============================================================

SET NAMES utf8mb4;

-- ============================================================
-- 阶段一：只读校验（不修改任何数据与结构）
-- ============================================================
SET @abort := NULL;

-- ---------- 1. 脏数据校验 ----------

-- 1a 同一 ORDER_NO 多张支付单（当前无唯一键，挡不住一单多支付单）
SET @dup := (SELECT COUNT(*) FROM (
  SELECT ORDER_NO FROM ws_payment GROUP BY ORDER_NO HAVING COUNT(*) > 1
) t);
SET @abort := IFNULL(@abort, IF(@dup > 0,
  'L2-DB 迁移中止：ws_payment 存在同一 ORDER_NO 的多张支付单，请人工核查，不自动合并删除', NULL));

-- 1b 同一 ORDER_ID 多张支付单
SET @dup := (SELECT COUNT(*) FROM (
  SELECT ORDER_ID FROM ws_payment GROUP BY ORDER_ID HAVING COUNT(*) > 1
) t);
SET @abort := IFNULL(@abort, IF(@dup > 0,
  'L2-DB 迁移中止：ws_payment 存在同一 ORDER_ID 的多张支付单，请人工核查，不自动合并删除', NULL));

-- 1c ws_wallet_flow 既有业务幂等键重复（列可能尚不存在，故用动态 SQL 保护）
-- 先判**表**存在：表缺失时若只判列会得到 @dup=0 放行，随后阶段二 ALTER 才 1146 炸裂，
-- 留下半应用 schema（"闸门前零副作用"在该路径上不成立）。故表缺失直接入中止闸。
SET @has_tbl := (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'ws_wallet_flow');
SET @abort := IFNULL(@abort, IF(@has_tbl = 0,
  'L2-DB 迁移中止：ws_wallet_flow 表不存在，请先完成基础表初始化', NULL));
SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'ws_wallet_flow' AND column_name = 'BIZ_IDEMPOTENCY_KEY');
SET @sql := IF(@has_col = 0, 'SET @dup := 0',
  'SET @dup := (SELECT COUNT(*) FROM (SELECT BIZ_IDEMPOTENCY_KEY FROM ws_wallet_flow
     WHERE BIZ_IDEMPOTENCY_KEY IS NOT NULL GROUP BY BIZ_IDEMPOTENCY_KEY HAVING COUNT(*) > 1) t)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @abort := IFNULL(@abort, IF(@dup > 0,
  'L2-DB 迁移中止：ws_wallet_flow 存在重复 BIZ_IDEMPOTENCY_KEY，财务幂等键不得复用，请人工核查', NULL));

-- 1d ws_domain_event 既有业务幂等键重复（同 1c：先判表存在）
SET @has_tbl := (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'ws_domain_event');
SET @abort := IFNULL(@abort, IF(@has_tbl = 0,
  'L2-DB 迁移中止：ws_domain_event 表不存在，请先完成基础表初始化', NULL));
SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'ws_domain_event' AND column_name = 'BIZ_IDEMPOTENCY_KEY');
SET @sql := IF(@has_col = 0, 'SET @dup := 0',
  'SET @dup := (SELECT COUNT(*) FROM (SELECT BIZ_IDEMPOTENCY_KEY FROM ws_domain_event
     WHERE BIZ_IDEMPOTENCY_KEY IS NOT NULL GROUP BY BIZ_IDEMPOTENCY_KEY HAVING COUNT(*) > 1) t)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @abort := IFNULL(@abort, IF(@dup > 0,
  'L2-DB 迁移中止：ws_domain_event 存在重复 BIZ_IDEMPOTENCY_KEY，请人工核查', NULL));

-- 1e ws_payment_event 既有事件唯一键重复（表可能尚不存在）
SET @has_tbl := (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'ws_payment_event');
SET @sql := IF(@has_tbl = 0, 'SET @dup := 0',
  'SET @dup := (SELECT COUNT(*) FROM (SELECT PAY_SOURCE, FACT_CHANNEL, PROVIDER_EVENT_KEY
     FROM ws_payment_event GROUP BY PAY_SOURCE, FACT_CHANNEL, PROVIDER_EVENT_KEY HAVING COUNT(*) > 1) t)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @abort := IFNULL(@abort, IF(@dup > 0,
  'L2-DB 迁移中止：ws_payment_event 存在重复 (PAY_SOURCE,FACT_CHANNEL,PROVIDER_EVENT_KEY) 事实，请人工核查', NULL));

-- ---------- 2. 既有同名索引结构校验（唯一性 / 列名 / 列序 / 无前缀 SUB_PART） ----------

-- 2a uk_payment_order_no：须唯一、恰好单列 ORDER_NO、非前缀索引
SET @n := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_payment' AND index_name = 'uk_payment_order_no');
SET @ok := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_payment' AND index_name = 'uk_payment_order_no'
    AND NON_UNIQUE = 0 AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'ORDER_NO' AND SUB_PART IS NULL);
SET @abort := IFNULL(@abort, IF(@n > 0 AND (@n <> 1 OR @ok <> 1),
  'L2-DB 迁移中止：已存在同名 uk_payment_order_no 但结构不符（须唯一、恰好单列 ORDER_NO、且非前缀索引）', NULL));

-- 2b uk_payment_order_id
SET @n := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_payment' AND index_name = 'uk_payment_order_id');
SET @ok := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_payment' AND index_name = 'uk_payment_order_id'
    AND NON_UNIQUE = 0 AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'ORDER_ID' AND SUB_PART IS NULL);
SET @abort := IFNULL(@abort, IF(@n > 0 AND (@n <> 1 OR @ok <> 1),
  'L2-DB 迁移中止：已存在同名 uk_payment_order_id 但结构不符（须唯一、恰好单列 ORDER_ID、且非前缀索引）', NULL));

-- 2c uk_wallet_flow_biz_key
SET @n := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_wallet_flow' AND index_name = 'uk_wallet_flow_biz_key');
SET @ok := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_wallet_flow' AND index_name = 'uk_wallet_flow_biz_key'
    AND NON_UNIQUE = 0 AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'BIZ_IDEMPOTENCY_KEY' AND SUB_PART IS NULL);
SET @abort := IFNULL(@abort, IF(@n > 0 AND (@n <> 1 OR @ok <> 1),
  'L2-DB 迁移中止：已存在同名 uk_wallet_flow_biz_key 但结构不符（须唯一、恰好单列 BIZ_IDEMPOTENCY_KEY、且非前缀索引）', NULL));

-- 2d uk_domain_event_biz_key
SET @n := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_domain_event' AND index_name = 'uk_domain_event_biz_key');
SET @ok := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_domain_event' AND index_name = 'uk_domain_event_biz_key'
    AND NON_UNIQUE = 0 AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'BIZ_IDEMPOTENCY_KEY' AND SUB_PART IS NULL);
SET @abort := IFNULL(@abort, IF(@n > 0 AND (@n <> 1 OR @ok <> 1),
  'L2-DB 迁移中止：已存在同名 uk_domain_event_biz_key 但结构不符（须唯一、恰好单列 BIZ_IDEMPOTENCY_KEY、且非前缀索引）', NULL));

-- 2e uk_payment_event_source_channel_key：三列复合、顺序固定、均无前缀
SET @n := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_payment_event'
    AND index_name = 'uk_payment_event_source_channel_key');
SET @ok := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_payment_event'
    AND index_name = 'uk_payment_event_source_channel_key'
    AND NON_UNIQUE = 0 AND SUB_PART IS NULL
    AND ((SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'PAY_SOURCE')
      OR (SEQ_IN_INDEX = 2 AND COLUMN_NAME = 'FACT_CHANNEL')
      OR (SEQ_IN_INDEX = 3 AND COLUMN_NAME = 'PROVIDER_EVENT_KEY')));
-- 表已存在时，唯一键必须**恰好正确**；@n = 0（唯一键根本不存在）同样不合格。
-- 曾写作 IF(@n > 0 AND ...)：与下方 CREATE TABLE IF NOT EXISTS 叠加会形成 money-path 假绿——
-- 表已存在但缺唯一键时不中止、建表又被静默跳过，迁移 rc=0 却没有防重复入账的最后防线。
SET @evt_tbl := (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'ws_payment_event');
SET @abort := IFNULL(@abort, IF(@evt_tbl > 0 AND (@n <> 3 OR @ok <> 3),
  'L2-DB 迁移中止：ws_payment_event 已存在但 uk_payment_event_source_channel_key 结构不符或缺失（须唯一、三列依次 PAY_SOURCE/FACT_CHANNEL/PROVIDER_EVENT_KEY、均非前缀）', NULL));

-- 2f ws_payment_event 表体：已存在则列集合必须完整（否则 CREATE TABLE IF NOT EXISTS 会静默跳过，
--    留下结构残缺的收件箱，故障延后到运行期）。37 列与本文件下方 CREATE TABLE 定义一致。
SET @evt_cols := (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'ws_payment_event'
    AND column_name IN ('ID','DATA_STATUS','CREATE_BY','CREATE_TIME','UPDATE_BY','UPDATE_TIME',
      'PAY_SOURCE','FACT_CHANNEL','PROVIDER_EVENT_KEY','PAYMENT_ID','ORDER_ID','ORDER_NO',
      'TRADE_STATE','TRANSACTION_ID','PAY_AMOUNT','CURRENCY','PAY_SUCCESS_TIME','RAW_BODY',
      'RAW_BODY_SHA256','VERIFY_METHOD','SIGNATURE_SERIAL','SIGNATURE_TIMESTAMP','SIGNATURE_NONCE',
      'SIGNATURE_VALUE','PROCESSING_STATUS','RETRY_COUNT','NEXT_RETRY_TIME','CLAIM_TIME','LEASE_UNTIL',
      'RECOVERY_APPROVAL_GROUP_KEY','RECOVERY_APPROVED_BY','RECOVERY_APPROVED_TIME',
      'RECOVERY_APPROVAL_REASON','LAST_ERROR','RECEIVED_TIME','PROCESSED_TIME','RAW_PURGED_TIME'));
SET @abort := IFNULL(@abort, IF(@evt_tbl > 0 AND @evt_cols <> 37,
  'L2-DB 迁移中止：ws_payment_event 已存在但列集合不完整，请人工核查后再迁移', NULL));

-- ---------- 3. 中止闸：任一校验未通过即在此以错误终止 ----------
-- 此时尚未执行任何 UPDATE/ALTER，schema 与数据零变化；也没有创建过任何存储过程。
SET @sql := IF(@abort IS NULL, 'DO 1',
  CONCAT('SELECT `', @abort, '` FROM (SELECT 1) AS l2db_abort'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- 阶段二：结构变更（仅在阶段一全部通过后执行；每步幂等）
-- ============================================================

-- ---------- 4. ws_payment 增量字段（契约 §5.2） ----------
SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'ws_payment' AND column_name = 'PAY_SOURCE') = 0,
  'ALTER TABLE ws_payment ADD COLUMN `PAY_SOURCE` tinyint NOT NULL DEFAULT 1 COMMENT ''支付来源：1微信 2Pay-Sim；由服务端支付适配器创建时写入，创建后不可改（L2-DB 契约§5.2）'' AFTER `PAY_STATUS`',
  'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'ws_payment' AND column_name = 'CURRENCY') = 0,
  'ALTER TABLE ws_payment ADD COLUMN `CURRENCY` varchar(16) NOT NULL DEFAULT ''CNY'' COMMENT ''币种，一期固定CNY（L2-DB 契约§5.2）；二期支持多币种前必须移除该默认'' AFTER `PAY_SOURCE`',
  'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'ws_payment' AND column_name = 'PAY_EXPIRE_TIME') = 0,
  'ALTER TABLE ws_payment ADD COLUMN `PAY_EXPIRE_TIME` varchar(14) NOT NULL DEFAULT '''' COMMENT ''创单时冻结的支付截止时间(契约§2.2算法)，创建后不可修改（L2-DB 契约§5.2）'' AFTER `CURRENCY`',
  'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'ws_payment' AND column_name = 'PAY_SUCCESS_TIME') = 0,
  'ALTER TABLE ws_payment ADD COLUMN `PAY_SUCCESS_TIME` varchar(14) NULL DEFAULT NULL COMMENT ''权威支付成功时间(Asia/Shanghai)（L2-DB 契约§5.2）'' AFTER `PAY_EXPIRE_TIME`',
  'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4b. 回填完成后立即撤销默认值（与 fresh init 权威结构收敛） ----------
-- DEFAULT 只是为了让存量行在严格模式下能补齐 NOT NULL；一旦加列完成就必须撤掉，否则：
--   ① 应用层漏写 PAY_SOURCE 会静默落成 1=微信（把 Pay-Sim 单记成真实收款，对账最坏方向）；
--   ② legacy 升级库与 fresh init 库结构不一致，"权威结构"名不副实。
-- 幂等：仅当该列仍带默认值时才 DROP。
SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'ws_payment' AND column_name = 'PAY_SOURCE' AND COLUMN_DEFAULT IS NOT NULL) > 0,
  'ALTER TABLE ws_payment ALTER COLUMN `PAY_SOURCE` DROP DEFAULT', 'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'ws_payment' AND column_name = 'PAY_EXPIRE_TIME' AND COLUMN_DEFAULT IS NOT NULL) > 0,
  'ALTER TABLE ws_payment ALTER COLUMN `PAY_EXPIRE_TIME` DROP DEFAULT', 'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 5. ws_payment 唯一键（契约 §5.1；不含 DATA_STATUS） ----------
SET @sql := IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'ws_payment' AND index_name = 'uk_payment_order_no') = 0,
  'ALTER TABLE ws_payment ADD UNIQUE KEY `uk_payment_order_no` (`ORDER_NO`)', 'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'ws_payment' AND index_name = 'uk_payment_order_id') = 0,
  'ALTER TABLE ws_payment ADD UNIQUE KEY `uk_payment_order_id` (`ORDER_ID`)', 'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 6. ws_payment_event 支付回调事实收件箱（契约 §5.3） ----------
-- 外部输入一律先落收件箱事实、再处理；不得"只信报文直接改账"。
CREATE TABLE IF NOT EXISTS `ws_payment_event` (
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

-- ---------- 7. 业务幂等键（契约 §5.1：充值入账固定 RECHARGE:<orderNo>） ----------
SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'ws_wallet_flow' AND column_name = 'BIZ_IDEMPOTENCY_KEY') = 0,
  'ALTER TABLE ws_wallet_flow ADD COLUMN `BIZ_IDEMPOTENCY_KEY` varchar(64) NULL DEFAULT NULL COMMENT ''业务幂等键，充值入账固定 RECHARGE:<orderNo>；不含DATA_STATUS，错误标记删除也不得复用'' AFTER `FLOW_REMARK`',
  'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'ws_wallet_flow' AND index_name = 'uk_wallet_flow_biz_key') = 0,
  'ALTER TABLE ws_wallet_flow ADD UNIQUE KEY `uk_wallet_flow_biz_key` (`BIZ_IDEMPOTENCY_KEY`)', 'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'ws_domain_event' AND column_name = 'BIZ_IDEMPOTENCY_KEY') = 0,
  'ALTER TABLE ws_domain_event ADD COLUMN `BIZ_IDEMPOTENCY_KEY` varchar(64) NULL DEFAULT NULL COMMENT ''业务幂等键，仅为需要数据库级幂等的领域事件提供稳定业务键'' AFTER `CONSUMED_FLAG`',
  'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'ws_domain_event' AND index_name = 'uk_domain_event_biz_key') = 0,
  'ALTER TABLE ws_domain_event ADD UNIQUE KEY `uk_domain_event_biz_key` (`BIZ_IDEMPOTENCY_KEY`)', 'DO 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

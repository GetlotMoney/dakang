-- E2E-04 售后退款与补偿 包D schema 迁移（REQ-061 权益批次模型）：
--   新表 ws_card_entitlement_batch —— 每笔充值一个权益批次，退款的唯一可信基准；
--   新表 ws_entitlement_allocation —— 每笔消费流水分摊到哪些批次；
--   字典 1374 批次状态、1375 批次来源。
--
-- 模式与包A/包B 一致：只读检查在前、任何变更在后、污染即中止且零变更；不使用存储过程。
-- 中止手法为动态 SQL SELECT `<中文原因>` 触发 ER_1054，事务外零残留。
--
-- 【这两张表存在的唯一理由】
-- 完整购卡/充值退款<b>不能</b>用 ws_card 的聚合余额去猜。聚合值只回答「现在还剩多少」，
-- 回答不了「这笔要退的充值，它带来的权益被用掉了多少」——同一张卡上有多笔充值时，
-- 聚合值里既有本笔的剩余也有别笔的剩余，按它退款必然错。批次把「哪一笔充值、发了多少、
-- 用掉多少、还剩多少」拆开记，退款才有确定的基准。
--
-- 本迁移是<b>纯新增</b>：不改任何既有表、不动任何既有数据。
-- 充值入账建批次、消费写分摊属包D 后续步骤，各自独立提交并各自回归。

SET NAMES utf8mb4;

-- ============ 只读检查区（不做任何变更） ============

SET @t_card := (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ws_card');
SET @sql := IF(@t_card = 1, 'SELECT 1', 'SELECT `中止：ws_card 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_flow := (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ws_wallet_flow');
SET @sql := IF(@t_flow = 1, 'SELECT 1', 'SELECT `中止：ws_wallet_flow 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_order := (SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = 'ws_order');
SET @sql := IF(@t_order = 1, 'SELECT 1', 'SELECT `中止：ws_order 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 同名异构表阻断：已存在的话必须带本迁移的要害列，否则停手交人工
SET @t_batch := (SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = 'ws_card_entitlement_batch');
SET @c_batch := (SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'ws_card_entitlement_batch'
                   AND column_name = 'REMAIN_AMOUNT_FEN');
SET @sql := IF(@t_batch = 0 OR @c_batch = 1, 'SELECT 1',
               'SELECT `中止：ws_card_entitlement_batch 已存在但缺少 REMAIN_AMOUNT_FEN，疑似同名异构表`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_alloc := (SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = 'ws_entitlement_allocation');
SET @c_alloc := (SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'ws_entitlement_allocation'
                   AND column_name = 'BATCH_ID');
SET @sql := IF(@t_alloc = 0 OR @c_alloc = 1, 'SELECT 1',
               'SELECT `中止：ws_entitlement_allocation 已存在但缺少 BATCH_ID，疑似同名异构表`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 字典段占位核验：1374/1375 必须空闲或已是本迁移写入的值
SET @d_conflict := (SELECT COUNT(*) FROM api_dict_type
                    WHERE (DICT_TYPE = '1374' AND DICT_NAME <> '权益批次状态')
                       OR (DICT_TYPE = '1375' AND DICT_NAME <> '权益批次来源'));
SET @sql := IF(@d_conflict = 0, 'SELECT 1', 'SELECT `中止：字典 1374/1375 已被其它业务占用`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @d_dup := (SELECT COUNT(*) FROM (
    SELECT DICT_TYPE, DICT_VALUE FROM api_dict_data
    WHERE DICT_TYPE IN ('1374', '1375')
    GROUP BY DICT_TYPE, DICT_VALUE HAVING COUNT(*) > 1) x);
SET @sql := IF(@d_dup = 0, 'SELECT 1', 'SELECT `中止：字典 1374/1375 已存在重复项，请先人工去重`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 变更区 ============

-- ------------------------------------------------------------------
-- 一、权益批次：每笔充值恰好一个
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ws_card_entitlement_batch` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间；同到期时间时的批次选取次序依据',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间',

  `CARD_ID`             bigint       NOT NULL COMMENT '所属水卡ID',
  `USER_ID`             bigint       NOT NULL COMMENT '卡主用户ID（分摊与退款的归属核验用）',
  `SOURCE_TYPE`         tinyint      NOT NULL COMMENT '批次来源(1375)：1首次购卡 2已有卡充值 3历史聚合（不可退）',
  `ORDER_ID`            bigint       NULL COMMENT '来源充值订单ID；历史聚合批次为NULL',
  `ORDER_NO`            varchar(32)  NULL COMMENT '来源充值订单号；历史聚合批次为NULL',
  `PAYMENT_ID`          bigint       NULL COMMENT '来源支付单ID；退款金额封顶按它聚合',
  `PACKAGE_ID`          bigint       NULL COMMENT '套餐ID',
  `PACKAGE_SNAP`        text         NULL COMMENT '套餐快照：折算公式的 payAmountFen / grantedWaterMl 只能取自这里，不查当前套餐',

  `PAY_AMOUNT_FEN`      bigint       NOT NULL DEFAULT 0 COMMENT '本批次对应的实付金额(分)；退款折算的分子基准',
  `GRANT_AMOUNT_FEN`    bigint       NOT NULL DEFAULT 0 COMMENT '本批次发放的余额权益(分)=本金+赠送',
  `GRANT_BONUS_FEN`     bigint       NOT NULL DEFAULT 0 COMMENT '其中赠送部分(分)；赠送已消费部分不退款',
  `GRANT_WATER_ML`      bigint       NOT NULL DEFAULT 0 COMMENT '本批次发放的水量权益(毫升)',
  `REMAIN_AMOUNT_FEN`   bigint       NOT NULL DEFAULT 0 COMMENT '剩余余额权益(分)；分摊与冲正的 CAS 前态',
  `REMAIN_WATER_ML`     bigint       NOT NULL DEFAULT 0 COMMENT '剩余水量权益(毫升)；同上',

  `EXPIRE_TIME`         varchar(14)  NULL COMMENT '本批次有效期；NULL=永久（D-213 付费卡永久）。批次选取按它升序，NULL 视为最晚',
  `SCOPE_JSON`          text         NULL COMMENT '本批次的可用范围快照',

  `BATCH_STATUS`        tinyint      NOT NULL COMMENT '批次状态(1374)：1可用 2退款锁定 3已退款 4已耗尽 5已过期 6不可退',
  `REFUND_LOCKED_BY`    bigint       NULL COMMENT '锁定该批次的售后动作ID；退款处理中禁止继续消费',
  `REFUND_LOCK_TIME`    varchar(14)  NULL COMMENT '锁定时间',
  `REFUNDED_AMOUNT_FEN` bigint       NOT NULL DEFAULT 0 COMMENT '已退款金额(分)；累计封顶基准之一',
  `VERSION`             int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，分摊与冲正 CAS 的前态条件',

  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_batch_order` (`ORDER_ID`),
  INDEX `idx_batch_pick` (`CARD_ID`, `BATCH_STATUS`, `EXPIRE_TIME`, `CREATE_TIME`),
  INDEX `idx_batch_payment` (`PAYMENT_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='水卡权益批次表（E2E-04 包D，REQ-061）';

-- ------------------------------------------------------------------
-- 二、消费分摊：每笔消费流水分摊到哪些批次
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ws_entitlement_allocation` (
  `ID`               bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：分摊是账本事实，保持0',
  `CREATE_BY`        bigint      NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14) NOT NULL COMMENT '更新时间',

  `BATCH_ID`         bigint      NOT NULL COMMENT '被扣减的权益批次ID',
  `CARD_ID`          bigint      NOT NULL COMMENT '水卡ID（冗余，便于按卡对账不必回表批次）',
  `FLOW_ID`          bigint      NULL COMMENT '对应的钱包流水ID',
  `ORDER_ID`         bigint      NULL COMMENT '触发消费的订单ID',
  `BIZ_KEY`          varchar(64) NOT NULL COMMENT '消费业务幂等键（如 DELIVERY:<orderNo>、DISPENSE:<orderNo>）',
  `ALLOC_SEQ`        int         NOT NULL DEFAULT 1 COMMENT '同一次消费跨多个批次时的分摊序号，从1开始',

  `ALLOC_AMOUNT_FEN` bigint      NOT NULL DEFAULT 0 COMMENT '本次从该批次扣减的余额(分)，正数',
  `ALLOC_WATER_ML`   bigint      NOT NULL DEFAULT 0 COMMENT '本次从该批次扣减的水量(毫升)，正数',
  `REVERSED_FLAG`    tinyint     NOT NULL DEFAULT 0 COMMENT '是否已被冲正：0否 1是；退款冲正只冲未冲正的分摊',

  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_alloc_biz_batch` (`BIZ_KEY`, `BATCH_ID`),
  INDEX `idx_alloc_batch` (`BATCH_ID`, `REVERSED_FLAG`),
  INDEX `idx_alloc_card` (`CARD_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='权益批次消费分摊表（E2E-04 包D，REQ-061）';

-- ------------------------------------------------------------------
-- 三、字典（NOT EXISTS 幂等；api_dict_data 除主键外无唯一键，IGNORE 无键可撞）
-- ------------------------------------------------------------------
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (
  SELECT '权益批次状态' AS DICT_NAME, '1374' AS DICT_TYPE, '水卡权益批次的生命周期状态' AS DICT_REMARK
  UNION ALL SELECT '权益批次来源', '1375', '权益批次的产生来源'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
  SELECT '1374' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '可用' AS DICT_LABEL
  UNION ALL SELECT '1374', 2, 2, '退款锁定'
  UNION ALL SELECT '1374', 3, 3, '已退款'
  UNION ALL SELECT '1374', 4, 4, '已耗尽'
  UNION ALL SELECT '1374', 5, 5, '已过期'
  UNION ALL SELECT '1374', 6, 6, '不可退'
  UNION ALL SELECT '1375', 1, 1, '首次购卡'
  UNION ALL SELECT '1375', 2, 2, '已有卡充值'
  UNION ALL SELECT '1375', 3, 3, '历史聚合'
) s
WHERE NOT EXISTS (
  SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE
);

-- ============ 后置不变式 ============

SET @ok_batch := (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'ws_card_entitlement_batch');
SET @sql := IF(@ok_batch = 1, 'SELECT 1', 'SELECT `中止：ws_card_entitlement_batch 建表未生效`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_alloc := (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'ws_entitlement_allocation');
SET @sql := IF(@ok_alloc = 1, 'SELECT 1', 'SELECT `中止：ws_entitlement_allocation 建表未生效`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 「每笔充值恰好一个批次」的物理保证。缺它则同一笔充值可以建出两个批次，
-- 退款时按哪个算都说不清，且两个批次的剩余权益合起来超过实际发放量。
SET @ok_uk1 := (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ws_card_entitlement_batch'
                  AND INDEX_NAME = 'uk_batch_order' AND NON_UNIQUE = 0);
SET @sql := IF(@ok_uk1 = 1, 'SELECT 1', 'SELECT `中止：uk_batch_order 未建立，一笔充值可能建出多个批次`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 「同一次消费对同一批次只分摊一次」的物理保证。缺它则重放一次消费事务
-- 会重复扣减批次剩余，卡聚合与批次剩余从此对不上。
SET @ok_uk2 := (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'ws_entitlement_allocation'
                  AND INDEX_NAME = 'uk_alloc_biz_batch' AND NON_UNIQUE = 0);
SET @sql := IF(@ok_uk2 = 1, 'SELECT 1', 'SELECT `中止：uk_alloc_biz_batch 未建立，消费重放会重复扣减批次`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_d := (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE IN ('1374', '1375'));
SET @sql := IF(@ok_d = 9, 'SELECT 1', 'SELECT `中止：权益批次字典项数不为9`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT 'E2E-04 包D 迁移完成：ws_card_entitlement_batch + ws_entitlement_allocation + 字典 1374/1375' AS RESULT;

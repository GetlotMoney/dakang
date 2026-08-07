-- E2E-04 包D 数据回填：为历史聚合权益建立 LEGACY_NON_REFUNDABLE 批次（REQ-061）。
--
-- 【为什么与建表迁移分开成一个文件】
-- 前一个文件（...-d.sql）是纯 schema 变更，不碰任何既有数据；本文件<b>写的是资金数据</b>。
-- 两者混在一起会让「建表」这件低风险的事被迫承担「改账面结构」的审查强度，
-- 也让回填无法单独重跑、单独校验、单独回滚。资金数据的写入必须能被单独审。
--
-- 【回填做什么，不做什么】
-- 做：给每张有剩余权益、但其权益无法归属到任何充值批次的卡，建一条 SOURCE_TYPE=3历史聚合、
--     BATCH_STATUS=6不可退 的批次，其剩余额度恰等于该卡当前聚合值减去已归属批次的部分。
-- 不做：<b>绝不反向伪造到历史充值订单</b>（任务书原文）。ORDER_ID / PAYMENT_ID 一律 NULL，
--     PAY_AMOUNT_FEN 恒为 0 —— 这些权益究竟对应哪一笔付款、付了多少，账本里没有答案，
--     猜一个填进去就等于凭空造出一个可退款的基准，那正是包D 要消灭的东西。
--     状态固定 6不可退：无法归属就无法折算，退款只能走人工。
--
-- 【幂等】
-- 每张卡至多一条历史聚合批次，由 NOT EXISTS 前置判定保证。
-- 注意 uk_batch_order 对 NULL 不去重，故物理唯一键在这里帮不上忙——
-- 这是本文件必须自己保证幂等的原因，也是它必须带后置不变式的原因。

SET NAMES utf8mb4;

-- ============ 只读检查区 ============

SET @t_batch := (SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = 'ws_card_entitlement_batch');
SET @sql := IF(@t_batch = 1, 'SELECT 1', 'SELECT `中止：ws_card_entitlement_batch 不存在，请先执行 ...-d.sql`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 已存在多条历史聚合批次的卡说明此前被非幂等地回填过。继续插入只会加深污染，
-- 且「该卡的历史权益共有多少」从此说不清。停手交人工。
SET @dup := (SELECT COUNT(*) FROM (
    SELECT CARD_ID FROM ws_card_entitlement_batch
    WHERE SOURCE_TYPE = 3 AND DATA_STATUS = 0
    GROUP BY CARD_ID HAVING COUNT(*) > 1) x);
SET @sql := IF(@dup = 0, 'SELECT 1', 'SELECT `中止：已有卡存在多条历史聚合批次，疑似此前被重复回填，请先人工去重`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 变更区 ============

-- 写入与全部后置不变式必须处于同一事务。任一断言失败时，批处理客户端会终止连接，
-- 未提交事务随连接关闭回滚，避免只给部分水卡留下历史批次。
START TRANSACTION;

-- 每张卡的「未被批次覆盖的剩余权益」= 卡聚合值 − 该卡已有批次的剩余合计。
-- 首次回填时右项为 0，故等于卡聚合值；若将来先有了充值批次再回填，也只补差额，不会重复计。
-- 差额 ≤ 0 的卡不建批次：它的权益已被批次完全覆盖，再建一条空批次只是噪音。
INSERT INTO `ws_card_entitlement_batch`
(`DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`,
 `CARD_ID`, `USER_ID`, `SOURCE_TYPE`, `ORDER_ID`, `ORDER_NO`, `PAYMENT_ID`,
 `PACKAGE_ID`, `PACKAGE_SNAP`,
 `PAY_AMOUNT_FEN`, `GRANT_AMOUNT_FEN`, `GRANT_BONUS_FEN`, `GRANT_WATER_ML`,
 `REMAIN_AMOUNT_FEN`, `REMAIN_WATER_ML`, `EXPIRE_TIME`, `SCOPE_JSON`,
 `BATCH_STATUS`, `REFUNDED_AMOUNT_FEN`, `VERSION`)
SELECT 0, 1, '20260729000000', 1, '20260729000000',
       c.ID, c.USER_ID, 3, NULL, NULL, NULL,
       NULL, NULL,
       0, GREATEST(c.BALANCE_AMOUNT - IFNULL(b.rem_fen, 0), 0), 0,
       GREATEST(c.BALANCE_ML - IFNULL(b.rem_ml, 0), 0),
       GREATEST(c.BALANCE_AMOUNT - IFNULL(b.rem_fen, 0), 0),
       GREATEST(c.BALANCE_ML - IFNULL(b.rem_ml, 0), 0),
       c.EXPIRE_TIME, c.SCOPE_JSON,
       6, 0, 1
FROM `ws_card` c
LEFT JOIN (
    SELECT CARD_ID, SUM(REMAIN_AMOUNT_FEN) AS rem_fen, SUM(REMAIN_WATER_ML) AS rem_ml
    FROM ws_card_entitlement_batch WHERE DATA_STATUS = 0 GROUP BY CARD_ID
) b ON b.CARD_ID = c.ID
WHERE c.DATA_STATUS = 0
  AND (c.BALANCE_AMOUNT - IFNULL(b.rem_fen, 0) > 0 OR c.BALANCE_ML - IFNULL(b.rem_ml, 0) > 0)
  AND NOT EXISTS (
      SELECT 1 FROM ws_card_entitlement_batch x
      WHERE x.CARD_ID = c.ID AND x.SOURCE_TYPE = 3 AND x.DATA_STATUS = 0);

-- ============ 后置不变式 ============

-- 每张卡至多一条历史聚合批次。破了说明幂等失效，重跑会持续制造权益。
SET @ok_one := (SELECT COUNT(*) FROM (
    SELECT CARD_ID FROM ws_card_entitlement_batch
    WHERE SOURCE_TYPE = 3 AND DATA_STATUS = 0
    GROUP BY CARD_ID HAVING COUNT(*) > 1) x);
SET @sql := IF(@ok_one = 0, 'SELECT 1', 'SELECT `中止：回填后出现每卡多条历史聚合批次`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 【本文件最重要的一条】批次剩余合计必须逐卡等于卡聚合值。
-- 这是 D-4 消费分摊能成立的全部前提：分摊要从批次里扣，批次总量对不上卡，
-- 要么分摊时批次先扣空（用户余额还在却用不了），要么批次有余而卡已空（凭空多出权益）。
SET @mismatch := (SELECT COUNT(*) FROM (
    SELECT c.ID
    FROM ws_card c
    LEFT JOIN (
        SELECT CARD_ID, SUM(REMAIN_AMOUNT_FEN) AS rem_fen, SUM(REMAIN_WATER_ML) AS rem_ml
        FROM ws_card_entitlement_batch WHERE DATA_STATUS = 0 GROUP BY CARD_ID
    ) b ON b.CARD_ID = c.ID
    WHERE c.DATA_STATUS = 0
      AND (IFNULL(b.rem_fen, 0) <> c.BALANCE_AMOUNT OR IFNULL(b.rem_ml, 0) <> c.BALANCE_ML)) x);
SET @sql := IF(@mismatch = 0, 'SELECT 1',
               'SELECT `中止：存在卡的批次剩余合计与卡聚合值不符，消费分摊的前提不成立`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 历史聚合批次一律不可退且不挂充值订单。任一条破了都意味着凭空造出了可退款基准。
SET @ok_legacy := (SELECT COUNT(*) FROM ws_card_entitlement_batch
                   WHERE SOURCE_TYPE = 3
                     AND (BATCH_STATUS <> 6 OR ORDER_ID IS NOT NULL
                          OR PAYMENT_ID IS NOT NULL OR PAY_AMOUNT_FEN <> 0));
SET @sql := IF(@ok_legacy = 0, 'SELECT 1',
               'SELECT `中止：历史聚合批次出现可退状态或被挂到充值订单上`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

COMMIT;

SELECT CONCAT('E2E-04 包D 历史权益回填完成：历史聚合批次 ',
              (SELECT COUNT(*) FROM ws_card_entitlement_batch WHERE SOURCE_TYPE = 3 AND DATA_STATUS = 0),
              ' 条，覆盖卡 ',
              (SELECT COUNT(*) FROM ws_card WHERE DATA_STATUS = 0),
              ' 张，批次剩余合计与卡聚合值逐卡一致') AS RESULT;

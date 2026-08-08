-- ============================================================
-- D-420 分润退款冲减闭环（S1，2026-08-07 首版 / 2026-08-08 R1 重写 / R2 增补动作级 outbox）
--
-- R1 重写说明：首版按 ws_refund 单锚+整单基数实现被复审驳回，本文件在主库执行前
-- 整体重写为 R1 口径——冲减以 ws_after_sale_action 为业务源（CARD_REFUND/
-- GATEWAY_REFUND），基数=实际退款额 REFUND_PRODUCT_FEN，多次部分退款由
-- ws_split_clawback 事实表承载（uk(ACTION_ID,SPLIT_ID)），REVERSED_AMOUNT 转为
-- 累计口径。首版从未执行主库，无历史包袱。
--
-- 内容：
-- 1. ws_split_record.REVERSED_AMOUNT（累计已冲减）
-- 2. ws_income_account.CLAWBACK_DEFICIT_FEN（冲减待补差额，>0 禁提现）
-- 3. ws_split_clawback 事实表 + 字典 1387
--
-- 幂等：information_schema 探测 / CREATE IF NOT EXISTS / NOT EXISTS 字典。
-- 主库执行须单独授权（本文件仅在独立临时库验证）。
-- ============================================================

SET NAMES utf8mb4;

SET @has_reversed := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ws_split_record'
    AND COLUMN_NAME = 'REVERSED_AMOUNT');

SET @ddl1 := IF(@has_reversed = 0,
  'ALTER TABLE `ws_split_record` ADD COLUMN `REVERSED_AMOUNT` bigint NOT NULL DEFAULT 0 COMMENT ''累计已冲减金额(分)（D-420 R1）：多次部分退款逐笔累加（明细在 ws_split_clawback）；结算与入账按净额=SPLIT_AMOUNT-本列；恒不超过行水费线原始份额。0=未冲减'' AFTER `REFUND_ID`',
  'SELECT ''REVERSED_AMOUNT already exists, skip'' AS note');

PREPARE stmt1 FROM @ddl1;
EXECUTE stmt1;
DEALLOCATE PREPARE stmt1;

SET @has_deficit := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ws_income_account'
    AND COLUMN_NAME = 'CLAWBACK_DEFICIT_FEN');

SET @ddl2 := IF(@has_deficit = 0,
  'ALTER TABLE `ws_income_account` ADD COLUMN `CLAWBACK_DEFICIT_FEN` bigint NOT NULL DEFAULT 0 COMMENT ''冲减待补差额(分)（D-420/C-04.1 选项B）：退款扣回时可用余额不足的缺口，恒>=0。>0 时禁止提现（fail-closed)；后续分润入账先补此差额再进可用余额，补足即解除限制；不记负余额、公司不兜底'' AFTER `FROZEN_FEN`',
  'SELECT ''CLAWBACK_DEFICIT_FEN already exists, skip'' AS note');

PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

CREATE TABLE IF NOT EXISTS `ws_split_clawback` (
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
CREATE TABLE IF NOT EXISTS `ws_split_clawback_action` (
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

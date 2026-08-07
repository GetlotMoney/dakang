-- ============================================================
-- E2E-08 支付、对账与分账 包A：分账/钱包/归因数据模型（幂等迁移）
--
-- 内容：①ws_split_record 补库层幂等唯一键与退款关联列（扩展位，本期不实现冲减）
-- ②新表 ws_split_config（比例配置，生效时间版本化）③新表 ws_income_account /
-- ws_income_flow（机主收益钱包，REQ-072 明令不得混用 ws_wallet_flow）
-- ④ws_user 本人邀请码列 + ws_order 推荐人快照列（归因，不挪用既有 CHANNEL/PROMO 列）
-- ⑤字典 1375 加值 4=运营赠卡；新段 1376~1381 ⑥取水 CONSUME 流水幂等键回填
-- 全部幂等：表/列/索引以 information_schema 判存，字典 INSERT IGNORE。
-- ============================================================

SET NAMES utf8mb4;

-- 前置：核心表必须存在
SET @ok := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
  AND table_name IN ('ws_split_record', 'ws_order', 'ws_user', 'ws_wallet_flow'));
SET @sql := IF(@ok = 4, 'SELECT 1', 'SELECT `中止：前置表缺失`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 1. ws_split_record 加固 ----------
-- 唯一键：同单同收款方恒一行（铁律②库层幂等，Worker 并发/重放零重复）
SET @has := (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
  AND table_name = 'ws_split_record' AND index_name = 'uk_split_order_receiver');
SET @sql := IF(@has > 0, 'SELECT 1',
  'ALTER TABLE `ws_split_record` ADD UNIQUE KEY `uk_split_order_receiver` (`ORDER_ID`,`RECEIVER_TYPE`,`RECEIVER_USER_ID`), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
-- 退款关联列（外部确认项「退款是否冲减分润」的扩展位；本期只留列不写逻辑，免二次迁移）
SET @has := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
  AND table_name = 'ws_split_record' AND column_name = 'REFUND_ID');
SET @sql := IF(@has > 0, 'SELECT 1',
  'ALTER TABLE `ws_split_record` ADD COLUMN `REFUND_ID` bigint NULL COMMENT ''触发回退的退款单ID（扩展位：退款冲减分润经甲方确认后启用，SPLIT_STATUS=4 时必填）'' AFTER `SPLIT_REMARK`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 2. 分账比例配置（生效时间版本化） ----------
CREATE TABLE IF NOT EXISTS `ws_split_config` (
  `ID`            bigint      NOT NULL AUTO_INCREMENT,
  `DATA_STATUS`   tinyint     NOT NULL DEFAULT 0,
  `CREATE_BY`     bigint      NOT NULL,
  `CREATE_TIME`   varchar(14) NOT NULL,
  `UPDATE_BY`     bigint      NOT NULL,
  `UPDATE_TIME`   varchar(14) NOT NULL,
  `PRODUCT_LINE`  tinyint     NOT NULL COMMENT '商品线(1376)：1售水 2配送',
  `RECEIVER_TYPE` tinyint     NOT NULL COMMENT '收款方类型(1377)：1机主 2配送员 3平台 4渠道(预留) 5推荐人(预留) 6区域服务商(预留)',
  `SPLIT_RATE`    int         NOT NULL COMMENT '比例（万分比，如 7000=70%；整除余数恒归平台）',
  `EFFECT_TIME`   varchar(14) NOT NULL COMMENT '生效时间（含）；取订单创建时点生效版本，变更不追溯',
  `CONFIG_REMARK` varchar(200) NULL,
  PRIMARY KEY (`ID`),
  -- 同线同收款方同生效时点恒一行：改比例=插新生效版本，历史版本只读（快照可审计）
  UNIQUE KEY `uk_split_config_version` (`PRODUCT_LINE`, `RECEIVER_TYPE`, `EFFECT_TIME`),
  INDEX `idx_split_config_effect` (`PRODUCT_LINE`, `EFFECT_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='分账比例配置表（版本化，演示值待甲方确认后调整）';

-- ---------- 3. 收益钱包（账户+流水，独立于 C 端水卡钱包） ----------
CREATE TABLE IF NOT EXISTS `ws_income_account` (
  `ID`             bigint      NOT NULL AUTO_INCREMENT,
  `DATA_STATUS`    tinyint     NOT NULL DEFAULT 0,
  `CREATE_BY`      bigint      NOT NULL,
  `CREATE_TIME`    varchar(14) NOT NULL,
  `UPDATE_BY`      bigint      NOT NULL,
  `UPDATE_TIME`    varchar(14) NOT NULL,
  `USER_ID`        bigint      NOT NULL COMMENT '收益人（ws_user.ID）；查询按会话强制过滤（铁律6）',
  `BALANCE_FEN`    bigint      NOT NULL DEFAULT 0 COMMENT '可用分润余额(分)；恒等于末笔流水 AFTER（对账不变式）',
  `FROZEN_FEN`     bigint      NOT NULL DEFAULT 0 COMMENT '提现审核冻结中(分)',
  `VERSION`        int         NOT NULL DEFAULT 1 COMMENT '乐观锁：余额变动走前值+VERSION 双条件 CAS',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_income_account_user` (`USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收益账户（分润余额，与水卡余额/用户余额物理隔离，REQ-072）';

CREATE TABLE IF NOT EXISTS `ws_income_flow` (
  `ID`             bigint      NOT NULL AUTO_INCREMENT,
  `DATA_STATUS`    tinyint     NOT NULL DEFAULT 0,
  `CREATE_BY`      bigint      NOT NULL,
  `CREATE_TIME`    varchar(14) NOT NULL,
  `UPDATE_BY`      bigint      NOT NULL,
  `UPDATE_TIME`    varchar(14) NOT NULL,
  `USER_ID`        bigint      NOT NULL COMMENT '收益人；按会话强制过滤（铁律6）',
  `FLOW_TYPE`      tinyint     NOT NULL COMMENT '收益流水类型(1378)：1分润入账 2分润回退(预留) 3提现冻结 4提现完成(预留) 5提现驳回解冻',
  `AMOUNT_FEN`     bigint      NOT NULL COMMENT '变动金额(分)，入账为正、出账为负',
  `AFTER_FEN`      bigint      NOT NULL COMMENT '变动后可用余额(分)；对账以逐笔连续为不变式',
  `SPLIT_ID`       bigint      NULL COMMENT '来源分账记录（1/2 类必填：余额变动可追溯到分账与原始订单，REQ-072）',
  `ORDER_NO`       varchar(32) NULL COMMENT '原始订单号快照（省联查，追溯口径与 SPLIT_ID 同源）',
  `BIZ_IDEMPOTENCY_KEY` varchar(64) NOT NULL COMMENT '幂等键：INCOME:<splitId> / WITHDRAW:<申请号>（前缀命名空间已登记，防截断碰撞）',
  `FLOW_REMARK`    varchar(200) NULL,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_income_flow_biz_key` (`BIZ_IDEMPOTENCY_KEY`),
  INDEX `idx_income_flow_user_time` (`USER_ID`, `CREATE_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收益流水（只插不更新；禁止与 ws_wallet_flow 混表，REQ-072 验收补充）';

-- ---------- 3b. 日对账（批任务 + 差异台账，REQ-023/REQ-066 financial_reconciliation 口径） ----------
CREATE TABLE IF NOT EXISTS `ws_reconcile_task` (
  `ID`            bigint      NOT NULL AUTO_INCREMENT,
  `DATA_STATUS`   tinyint     NOT NULL DEFAULT 0,
  `CREATE_BY`     bigint      NOT NULL,
  `CREATE_TIME`   varchar(14) NOT NULL,
  `UPDATE_BY`     bigint      NOT NULL,
  `UPDATE_TIME`   varchar(14) NOT NULL,
  `BIZ_DATE`      varchar(8)  NOT NULL COMMENT '账期日（yyyyMMdd，日切按流水/事实 CREATE_TIME 前缀）',
  `TASK_STATUS`   tinyint     NOT NULL COMMENT '对账任务状态(1380)：1执行中 2平账 3有差异',
  `CHECK_TOTAL`   int         NOT NULL DEFAULT 0 COMMENT '本批核对项数',
  `DIFF_TOTAL`    int         NOT NULL DEFAULT 0 COMMENT '差异项数',
  `TASK_REMARK`   varchar(500) NULL,
  PRIMARY KEY (`ID`),
  -- 同账期恒一行：重跑=先删旧差异再重算（差异台账以最新一轮为准），任务行原位更新
  UNIQUE KEY `uk_reconcile_task_date` (`BIZ_DATE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='日对账批任务';

CREATE TABLE IF NOT EXISTS `ws_reconcile_diff` (
  `ID`            bigint      NOT NULL AUTO_INCREMENT,
  `DATA_STATUS`   tinyint     NOT NULL DEFAULT 0,
  `CREATE_BY`     bigint      NOT NULL,
  `CREATE_TIME`   varchar(14) NOT NULL,
  `UPDATE_BY`     bigint      NOT NULL,
  `UPDATE_TIME`   varchar(14) NOT NULL,
  `TASK_ID`       bigint      NOT NULL COMMENT '所属对账批',
  `BIZ_DATE`      varchar(8)  NOT NULL,
  `DIFF_TYPE`     tinyint     NOT NULL COMMENT '差异分类(1379)：1单边账 2金额不符 3状态不符 4账本断裂',
  `CHECK_DIMENSION` varchar(30) NOT NULL COMMENT '核对维度：payment-fact/order-flow/card-ledger/split-sum/income-ledger',
  `BIZ_KEY`       varchar(64) NOT NULL COMMENT '差异主体业务键（订单号/卡ID/账户ID）',
  `EXPECTED_VAL`  varchar(200) NULL COMMENT '期望值',
  `ACTUAL_VAL`    varchar(200) NULL COMMENT '实际值',
  `DIFF_REMARK`   varchar(500) NULL,
  PRIMARY KEY (`ID`),
  INDEX `idx_reconcile_diff_task` (`TASK_ID`),
  INDEX `idx_reconcile_diff_key` (`BIZ_DATE`, `BIZ_KEY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='日对账差异台账（每轮重算整批替换）';

-- ---------- 4. 归因列 ----------
-- ws_user 本人邀请码（不挪用 PROMO_CODE——那是「注册时填入的他人码」，一列两义必混淆）
SET @has := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
  AND table_name = 'ws_user' AND column_name = 'OWN_INVITE_CODE');
SET @sql := IF(@has > 0, 'SELECT 1',
  'ALTER TABLE `ws_user` ADD COLUMN `OWN_INVITE_CODE` varchar(12) NULL COMMENT ''本人邀请码（确定性派生，唯一）；与推送码是否合一属外部确认项，语义保持中性'' AFTER `PROMO_CODE`, ADD UNIQUE KEY `uk_user_invite_code` (`OWN_INVITE_CODE`)');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
-- ws_order 推荐人快照（不挪用 CHANNEL_USER_ID——渠道与邀请人是两种归属）
SET @has := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
  AND table_name = 'ws_order' AND column_name = 'REFERRER_USER_ID');
SET @sql := IF(@has > 0, 'SELECT 1',
  'ALTER TABLE `ws_order` ADD COLUMN `REFERRER_USER_ID` bigint NULL COMMENT ''下单时推荐人快照（分润归因依据，随归属变更不回溯；绑定前订单恒 NULL）'' AFTER `CHANNEL_USER_ID`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 5. 字典 ----------
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`) VALUES
(NULL,1,'1375',4,4,'运营赠卡');
INSERT IGNORE INTO `api_dict_type`(`DICT_NAME`,`DICT_TYPE`,`DICT_REMARK`) VALUES
('分账商品线','1376','分账比例配置的商品线维度'),
('分账收款方类型','1377','与 ws_split_record.RECEIVER_TYPE 同源'),
('收益流水类型','1378','机主收益钱包流水（独立于 1344 水卡钱包）'),
('对账差异分类','1379','日对账差错台账'),
('对账任务状态','1380','日对账批任务'),
('提现审核状态','1381','提现申请骨架（不做真实出金）');
INSERT IGNORE INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`) VALUES
(NULL,1,'1376',1,1,'售水'),(NULL,1,'1376',2,2,'配送'),
(NULL,1,'1377',1,1,'机主'),(NULL,1,'1377',2,2,'配送员'),(NULL,1,'1377',3,3,'平台'),
(NULL,1,'1377',4,4,'渠道(预留)'),(NULL,1,'1377',5,5,'推荐人(预留)'),(NULL,1,'1377',6,6,'区域服务商(预留)'),
(NULL,1,'1378',1,1,'分润入账'),(NULL,1,'1378',2,2,'分润回退(预留)'),(NULL,1,'1378',3,3,'提现冻结'),
(NULL,1,'1378',4,4,'提现完成(预留)'),(NULL,1,'1378',5,5,'提现驳回解冻'),
(NULL,1,'1379',1,1,'单边账'),(NULL,1,'1379',2,2,'金额不符'),(NULL,1,'1379',3,3,'状态不符'),(NULL,1,'1379',4,4,'账本断裂'),
(NULL,1,'1380',1,1,'执行中'),(NULL,1,'1380',2,2,'平账'),(NULL,1,'1380',3,3,'有差异'),
(NULL,1,'1381',1,1,'待审核'),(NULL,1,'1381',2,2,'审核通过(预留)'),(NULL,1,'1381',3,3,'已驳回');

-- ---------- 6. 取水 CONSUME 流水幂等键回填（日对账去重前提） ----------
UPDATE `ws_wallet_flow` f JOIN `ws_order` o ON f.ORDER_ID = o.ID
SET f.BIZ_IDEMPOTENCY_KEY = CONCAT('CONSUME:', o.ORDER_NO)
WHERE f.FLOW_TYPE = 2 AND f.BIZ_IDEMPOTENCY_KEY IS NULL;

-- ---------- 7. PC 菜单与权限（列清单与 03-demo-baseline 逐列一致；插入+复活+绑定） ----------
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,
 `MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,
 `DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`) VALUES
(1028,'分账明细',2,NULL,1004,0,'split','/order/split',1,NULL,NULL,1,1,0,1,'20260731120000',1,'20260731120000'),
(1029,'日对账',2,NULL,1004,0,'reconcile','/order/reconcile',1,NULL,NULL,1,1,0,1,'20260731120000',1,'20260731120000'),
(1032,'分账比例配置',2,NULL,1004,0,'splitconfig','/order/splitconfig',1,NULL,NULL,1,1,0,1,'20260731120000',1,'20260731120000'),
(1145,'对账触发',3,NULL,1029,1,NULL,NULL,1,'finance:reconcile:run','finance:reconcile:run',2,1,0,1,'20260731120000',1,'20260731120000'),
(1146,'比例配置变更',3,NULL,1032,1,NULL,NULL,1,'finance:config:edit','finance:config:edit',2,1,0,1,'20260731120000',1,'20260731120000'),
(1147,'赠卡发放',3,NULL,1041,2,NULL,NULL,1,'user:card:issue','user:card:issue',2,1,0,1,'20260731120000',1,'20260731120000'),
(1148,'提现审核',3,NULL,1028,1,NULL,NULL,1,'finance:withdraw:audit','finance:withdraw:audit',2,1,0,1,'20260731120000',1,'20260731120000');

UPDATE `api_rbac_role_menu`
SET `DATA_STATUS` = 0, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260731120000'
WHERE `ROLE_ID` = 1 AND `MENU_ID` IN (1028, 1029, 1032, 1145, 1146, 1147, 1148) AND `DATA_STATUS` <> 0;

INSERT INTO `api_rbac_role_menu`
(`DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `ROLE_ID`, `MENU_ID`)
SELECT 0, 1, '20260731120000', 1, '20260731120000', 1, m.ID
FROM api_rbac_menu m
WHERE m.ID IN (1028, 1029, 1032, 1145, 1146, 1147, 1148)
  AND NOT EXISTS (SELECT 1 FROM api_rbac_role_menu rm WHERE rm.ROLE_ID = 1 AND rm.MENU_ID = m.ID);

-- 后置不变式
SET @ok := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
  AND table_name IN ('ws_split_config', 'ws_income_account', 'ws_income_flow'))
  + (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
     AND table_name = 'ws_split_record' AND index_name = 'uk_split_order_receiver' AND SEQ_IN_INDEX = 1);
SET @sql := IF(@ok = 4, 'SELECT ''E2E-08 包A 迁移完成：分账/钱包/归因数据模型'' AS RESULT',
  'SELECT `中止：包A 后置不变式未满足`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

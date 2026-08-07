-- E2E-04 售后退款与补偿 包B schema 迁移：
--   改造 ws_refund —— 从「单一微信退款单」升级为「支付机构退款单」（R0-8 / 任务书 3.2）；
--   新建 ws_refund_event —— 退款事实收件箱，形状对齐 ws_payment_event（任务书 3.3）；
--   字典 1373 退款来源；1343 退款状态补 4待重试 / 5需人工对账。
--
-- 模式与 2026-07-29-aftersale-e2e04-a.sql 一致：只读检查在前、任何变更在后、污染即中止且零变更；
-- 不使用存储过程。中止手法为动态 SQL SELECT `<中文原因>` 触发 ER_1054，事务外零残留。
-- 凡引用「可能不存在的列」的 SQL 一律走 PREPARE 动态执行（IF() 跳过求值但跳不过解析）。
--
-- 【与包A 的关键区别：本迁移含 ALTER，不是纯新增】
-- ws_refund 在 02-ws-business.sql 里已建表。ALTER 是有破坏性的，因此每一处
-- ADD COLUMN / CHANGE COLUMN 都必须先探测「该列是否已存在」再决定是否执行，
-- 否则重跑第二次会以 ER_DUP_FIELDNAME 中断在半途，留下改了一半的表。

SET NAMES utf8mb4;

-- ============ 只读检查区（不做任何变更） ============

-- 1) 依赖表必须存在
SET @t_refund := (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'ws_refund');
SET @sql := IF(@t_refund = 1, 'SELECT 1', 'SELECT `中止：ws_refund 表不存在，请先执行 init/02-ws-business.sql`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_payment := (SELECT COUNT(*) FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = 'ws_payment');
SET @sql := IF(@t_payment = 1, 'SELECT 1', 'SELECT `中止：ws_payment 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 包A 必须先行：退款单要 AFTER_SALE_ID 唯一关联到售后动作
SET @t_action := (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'ws_after_sale_action');
SET @sql := IF(@t_action = 1, 'SELECT 1', 'SELECT `中止：ws_after_sale_action 不存在，请先执行包A 迁移`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) 同名异构表阻断：ws_refund_event 若已存在，必须带本迁移的要害列，
--    否则说明是别处建的同名表，停手交人工——绝不 ALTER 一张来历不明的表。
SET @t_revent := (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'ws_refund_event');
SET @c_revent_key := (SELECT COUNT(*) FROM information_schema.columns
                      WHERE table_schema = DATABASE() AND table_name = 'ws_refund_event'
                        AND column_name = 'PROVIDER_EVENT_KEY');
SET @sql := IF(@t_revent = 0 OR @c_revent_key = 1, 'SELECT 1',
               'SELECT `中止：ws_refund_event 已存在但缺少 PROVIDER_EVENT_KEY，疑似同名异构表`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) ws_refund 改造前置：只有 REFUND_SOURCE 尚不存在时，存量行才无法安全回填来源。
--    已完成迁移的库会正常产生退款单；此时重跑全目录迁移必须允许有数据，但每行来源必须合法。
--    否则「迁移成功→产生退款→下次部署重跑迁移」会在正确数据上永久失败。
SET @r_rows := (SELECT COUNT(*) FROM ws_refund);
SET @c_refund_source := (SELECT COUNT(*) FROM information_schema.columns
                         WHERE table_schema = DATABASE() AND table_name = 'ws_refund'
                           AND column_name = 'REFUND_SOURCE');
SET @sql := IF(@c_refund_source = 1 OR @r_rows = 0, 'SELECT 1',
               'SELECT `中止：未迁移的 ws_refund 存在存量行，REFUND_SOURCE 无法安全回填，请人工确认来源后再迁移`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 已迁移数据态的幂等重跑：REFUND_SOURCE 已存在时，NULL/未知值同样不能放行。
-- 列可能不存在，必须动态执行，避免 SQL 解析阶段直接报 Unknown column。
SET @invalid_refund_source := 0;
SET @sql := IF(@c_refund_source = 1,
               'SELECT COUNT(*) INTO @invalid_refund_source FROM ws_refund WHERE REFUND_SOURCE IS NULL OR REFUND_SOURCE NOT IN (1,2)',
               'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql := IF(@invalid_refund_source = 0, 'SELECT 1',
               'SELECT `中止：ws_refund 存在非法 REFUND_SOURCE，须先人工对账`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 4) 字典段占位核验：1373 必须空闲或已是本迁移写入的值
SET @d_conflict := (SELECT COUNT(*) FROM api_dict_type
                    WHERE DICT_TYPE = '1373' AND DICT_NAME <> '退款来源');
SET @sql := IF(@d_conflict = 0, 'SELECT 1', 'SELECT `中止：字典 1373 已被其它业务占用`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 5) 污染阻断：目标字典项若已有重复（同 DICT_TYPE+DICT_VALUE 多行），说明此前被非幂等写法插过，
--    继续插入只会加深污染。停手交人工去重后重跑。
SET @d_dup := (SELECT COUNT(*) FROM (
    SELECT DICT_TYPE, DICT_VALUE FROM api_dict_data
    WHERE DICT_TYPE IN ('1373', '1343')
    GROUP BY DICT_TYPE, DICT_VALUE HAVING COUNT(*) > 1) x);
SET @sql := IF(@d_dup = 0, 'SELECT 1', 'SELECT `中止：字典 1373/1343 已存在重复项，请先人工去重`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 变更区 ============

-- ------------------------------------------------------------------
-- 一、改造 ws_refund：单一微信退款单 → 支付机构退款单
--
-- 每一列都先探测再执行：本迁移必须可重复执行，而 ALTER 撞已存在的列会中断在半途。
-- ------------------------------------------------------------------

-- AFTER_SALE_ID：与售后动作一一对应。可空（保留非售后来源的退款单空间），
-- 唯一索引对 NULL 不去重，故「多张无售后来源的退款单」不会互相撞键，
-- 而「同一个售后动作发起两张退款单」会在这里被物理挡住 —— 那正是重复退款的形状。
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'AFTER_SALE_ID');
SET @sql := IF(@c = 1, 'SELECT 1',
  'ALTER TABLE ws_refund ADD COLUMN `AFTER_SALE_ID` bigint NULL COMMENT ''关联售后动作ID(ws_after_sale_action.ID)；一个售后动作至多一张退款单'' AFTER `PAYMENT_ID`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'ORDER_NO');
SET @sql := IF(@c = 1, 'SELECT 1',
  'ALTER TABLE ws_refund ADD COLUMN `ORDER_NO` varchar(32) NOT NULL DEFAULT '''' COMMENT ''商户订单号，与退款事实的 ORDER_NO 对齐用'' AFTER `ORDER_ID`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- REFUND_SOURCE 与 ws_payment.PAY_SOURCE 同语义：由服务端适配器给出，绝不取自报文或前端。
-- 无默认值：漏填即插入失败，好过静默记成微信。
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'REFUND_SOURCE');
SET @sql := IF(@c = 1, 'SELECT 1',
  'ALTER TABLE ws_refund ADD COLUMN `REFUND_SOURCE` tinyint NOT NULL COMMENT ''退款来源(1373)：1微信 2Refund-Sim；服务端适配器常量，不取自报文'' AFTER `REFUND_AMOUNT`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'CURRENCY');
SET @sql := IF(@c = 1, 'SELECT 1',
  'ALTER TABLE ws_refund ADD COLUMN `CURRENCY` varchar(16) NOT NULL DEFAULT ''CNY'' COMMENT ''币种；退款事实回填时必须与本列一致'' AFTER `REFUND_SOURCE`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- WX_REFUND_ID → PROVIDER_REFUND_ID：列名里写死「WX」会让 Refund-Sim 的退款号
-- 存进一个叫「微信退款单号」的列，读的人无从判断这笔到底是不是真实微信退款。
-- 全仓对该列零引用（无 PO、无 mapper、无前端），改名不破坏任何调用方。
SET @c_old := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'WX_REFUND_ID');
SET @c_new := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'PROVIDER_REFUND_ID');
SET @sql := IF(@c_new = 1, 'SELECT 1',
           IF(@c_old = 1,
  'ALTER TABLE ws_refund CHANGE COLUMN `WX_REFUND_ID` `PROVIDER_REFUND_ID` varchar(64) NULL COMMENT ''支付机构退款单号(max64)；微信为refund_id，Refund-Sim为模拟号''',
  'ALTER TABLE ws_refund ADD COLUMN `PROVIDER_REFUND_ID` varchar(64) NULL COMMENT ''支付机构退款单号(max64)；微信为refund_id，Refund-Sim为模拟号'''));
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'REQUEST_TIME');
SET @sql := IF(@c = 1, 'SELECT 1',
  'ALTER TABLE ws_refund ADD COLUMN `REQUEST_TIME` varchar(14) NULL COMMENT ''退款请求发出时间''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'SUCCESS_TIME');
SET @sql := IF(@c = 1, 'SELECT 1',
  'ALTER TABLE ws_refund ADD COLUMN `SUCCESS_TIME` varchar(14) NULL COMMENT ''支付机构确认退款成功的时间（取自事实，不取本地时钟）''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 重试与对账列。VERSION 是状态机 CAS 的前态之一，与 ws_after_sale_action 同一套做法。
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'VERSION');
SET @sql := IF(@c = 1, 'SELECT 1',
  'ALTER TABLE ws_refund ADD COLUMN `VERSION` int NOT NULL DEFAULT 1 COMMENT ''乐观锁版本，退款状态机CAS的前态条件之一''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'RETRY_COUNT');
SET @sql := IF(@c = 1, 'SELECT 1',
  'ALTER TABLE ws_refund ADD COLUMN `RETRY_COUNT` int NOT NULL DEFAULT 0 COMMENT ''重试次数''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'NEXT_RETRY_TIME');
SET @sql := IF(@c = 1, 'SELECT 1',
  'ALTER TABLE ws_refund ADD COLUMN `NEXT_RETRY_TIME` varchar(14) NULL COMMENT ''下次可重试时间''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'LAST_ERROR');
SET @sql := IF(@c = 1, 'SELECT 1',
  'ALTER TABLE ws_refund ADD COLUMN `LAST_ERROR` varchar(500) NULL COMMENT ''最近一次失败原因；不写密钥、报文原文与本机路径''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 唯一索引：一个售后动作至多一张退款单。这是「重复退款」的物理闸，
-- 不靠应用层查重（铁律②）。NULL 不参与唯一性，非售后来源的退款单不受影响。
SET @i := (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND index_name = 'uk_refund_after_sale');
SET @sql := IF(@i > 0, 'SELECT 1',
  'ALTER TABLE ws_refund ADD UNIQUE INDEX `uk_refund_after_sale` (`AFTER_SALE_ID`)');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @i := (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND index_name = 'idx_refund_claim');
SET @sql := IF(@i > 0, 'SELECT 1',
  'ALTER TABLE ws_refund ADD INDEX `idx_refund_claim` (`REFUND_STATUS`, `NEXT_RETRY_TIME`)');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 支付单维度的累计封顶要按 PAYMENT_ID 聚合已成功退款额，没有这条索引会退化成全表扫
SET @i := (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND index_name = 'idx_refund_payment');
SET @sql := IF(@i > 0, 'SELECT 1',
  'ALTER TABLE ws_refund ADD INDEX `idx_refund_payment` (`PAYMENT_ID`, `REFUND_STATUS`)');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 二、新建 ws_refund_event —— 退款事实收件箱
--
-- 形状对齐 ws_payment_event（同一套 claim / 租约 / 重试语义），理由：
-- 退款事实与支付事实面对的是同一类问题（重复、乱序、迟到、错金额、处理者崩溃），
-- 两套形状会让 Worker 与恢复逻辑各写一遍，而其中任何一份写错都直接是资金事故。
--
-- R0-8：Refund-Sim 只产生「退款服务方事实」，事实先进本表，再由 Worker 核验后推进
-- ws_refund 与售后动作。适配器不得直接把退款改成成功。
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ws_refund_event` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：退款事实必须保持0，不做业务逻辑删除',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间',

  `REFUND_SOURCE`       tinyint      NOT NULL COMMENT '退款来源(1373)：1微信 2Refund-Sim（服务端适配器常量，不取自报文）',
  `FACT_CHANNEL`        tinyint      NOT NULL COMMENT '事实渠道：1通知 2查询 3Refund-Sim',
  `PROVIDER_EVENT_KEY`  varchar(100) NOT NULL COMMENT '外部事实键：通知id / Q:<sha256规范化查询>；与来源+渠道构成幂等键',

  `REFUND_ID`           bigint       NULL COMMENT '可信共键关联后的退款单ID，未知/错位时为空',
  `AFTER_SALE_ID`       bigint       NULL COMMENT '可信共键关联后的售后动作ID，未知/错位时为空',
  `ORDER_ID`            bigint       NULL COMMENT '可信共键关联后的订单ID，未知/错位时为空',
  `REFUND_NO`           varchar(32)  NOT NULL COMMENT '已验证的商户退款单号(out_refund_no)',
  `ORDER_NO`            varchar(32)  NULL COMMENT '事实携带的商户订单号，用于交叉核对',

  `REFUND_STATE`        varchar(32)  NOT NULL COMMENT '规范化退款事实状态：SUCCESS/PROCESSING/CLOSED/ABNORMAL/UNKNOWN',
  `PROVIDER_REFUND_ID`  varchar(64)  NULL COMMENT '支付机构退款单号；SUCCESS必填',
  `REFUND_AMOUNT`       bigint       NULL COMMENT '支付机构返回退款金额(分)；SUCCESS必填，未返回必须为空，禁止用内部值顶替',
  `CURRENCY`            varchar(16)  NULL COMMENT '支付机构返回币种；SUCCESS必填且为CNY',
  `REFUND_SUCCESS_TIME` varchar(14)  NULL COMMENT '退款成功时间(Asia/Shanghai)；SUCCESS必填',

  `RAW_BODY`            mediumtext   NULL COMMENT '原始签名正文/受保护查询证据；禁止出接口',
  `RAW_BODY_SHA256`     char(64)     NOT NULL COMMENT '正文完整性摘要：同键重复到达时比对，不一致即转人工',
  `VERIFY_METHOD`       tinyint      NOT NULL COMMENT '校验方式：1微信签名 2微信查询 3Refund-Sim HMAC',

  `PROCESSING_STATUS`   tinyint      NOT NULL COMMENT '处理状态：1待处理 2处理中 3已处理 4待重试 5需对账',
  `RETRY_COUNT`         int          NOT NULL DEFAULT 0 COMMENT '重试次数',
  `NEXT_RETRY_TIME`     varchar(14)  NULL COMMENT '下次可claim时间',
  `CLAIM_TIME`          varchar(14)  NULL COMMENT 'Worker claim 时间',
  `LEASE_UNTIL`         varchar(14)  NULL COMMENT 'claim 租约到期时间（处理者崩溃后据此恢复）',
  `LAST_ERROR`          varchar(500) NULL COMMENT '最近一次结构化失败原因；不写密钥与报文原文',
  `RECEIVED_TIME`       varchar(14)  NOT NULL COMMENT '服务端接收时间',
  `PROCESSED_TIME`      varchar(14)  NULL COMMENT '该事实完成业务处理的时间',

  PRIMARY KEY (`ID`),
  -- 幂等键三元组与 ws_payment_event 同构：同一来源同一渠道的同一事实只会有一行。
  -- 「同事实重复三次仍只有一张退款单」（场景13）由本键在数据库层保证，不靠应用查重。
  UNIQUE INDEX `uk_refund_event_source_channel_key` (`REFUND_SOURCE`, `FACT_CHANNEL`, `PROVIDER_EVENT_KEY`),
  INDEX `idx_refund_event_refund_no` (`REFUND_NO`),
  -- Worker 预筛：状态 + 到期时间，与 claim 的 WHERE 同构
  INDEX `idx_refund_event_claim` (`PROCESSING_STATUS`, `NEXT_RETRY_TIME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='退款事实收件箱表（E2E-04 包B）';

-- ------------------------------------------------------------------
-- 三、字典
--
-- 幂等只能靠 NOT EXISTS，不能靠 INSERT IGNORE：api_dict_type / api_dict_data
-- 除主键外没有任何唯一键，IGNORE 无键可撞，重复执行会静默插入整套重复项。
-- ------------------------------------------------------------------
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (
  SELECT '退款来源' AS DICT_NAME, '1373' AS DICT_TYPE, '支付机构退款的来源；页面据此显示 Refund-Sim' AS DICT_REMARK
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
  SELECT '1373' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '微信' AS DICT_LABEL
  UNION ALL SELECT '1373', 2, 2, 'Refund-Sim'
  -- 退款状态补两档：没有它们，一笔卡在重试或需人工核对的退款只能记成「退款失败」，
  -- 而失败与待重试对运营是两种完全不同的处置（一个等系统重试，一个必须人工介入）
  UNION ALL SELECT '1343', 4, 4, '待重试'
  UNION ALL SELECT '1343', 5, 5, '需人工对账'
) s
WHERE NOT EXISTS (
  SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE
);

-- ============ 后置不变式（不满足即中止） ============

SET @ok_revent := (SELECT COUNT(*) FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = 'ws_refund_event');
SET @sql := IF(@ok_revent = 1, 'SELECT 1', 'SELECT `中止：ws_refund_event 建表未生效`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 事实幂等键缺失 = 场景13「同事实重复三次仍只有一张退款单」失去物理保证
SET @ok_evkey := (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.statistics
                  WHERE table_schema = DATABASE() AND table_name = 'ws_refund_event'
                    AND INDEX_NAME = 'uk_refund_event_source_channel_key' AND NON_UNIQUE = 0);
SET @sql := IF(@ok_evkey = 1, 'SELECT 1', 'SELECT `中止：ws_refund_event 事实幂等键未建立`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 一个售后动作至多一张退款单：这条唯一键是重复退款的物理闸
SET @ok_asuk := (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = 'ws_refund'
                   AND INDEX_NAME = 'uk_refund_after_sale' AND NON_UNIQUE = 0);
SET @sql := IF(@ok_asuk = 1, 'SELECT 1', 'SELECT `中止：uk_refund_after_sale 唯一键未建立，重复退款失去物理约束`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ws_refund 的九个新列缺任一列，退款状态机或对账口径就不完整
SET @ok_rcols := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE() AND table_name = 'ws_refund'
                    AND column_name IN ('AFTER_SALE_ID', 'ORDER_NO', 'REFUND_SOURCE', 'CURRENCY',
                                        'PROVIDER_REFUND_ID', 'REQUEST_TIME', 'SUCCESS_TIME',
                                        'VERSION', 'RETRY_COUNT', 'NEXT_RETRY_TIME', 'LAST_ERROR'));
SET @sql := IF(@ok_rcols = 11, 'SELECT 1', 'SELECT `中止：ws_refund 改造列不齐`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 旧的单一微信命名必须已消失：两列并存时写入方可能挑错列，对账读另一列即为空
SET @ok_noold := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE() AND table_name = 'ws_refund' AND column_name = 'WX_REFUND_ID');
SET @sql := IF(@ok_noold = 0, 'SELECT 1', 'SELECT `中止：WX_REFUND_ID 仍存在，与 PROVIDER_REFUND_ID 并存会让退款号写入分叉`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_d1373 := (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE = '1373');
SET @sql := IF(@ok_d1373 = 2, 'SELECT 1', 'SELECT `中止：退款来源字典 1373 不为2项`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_d1343 := (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE = '1343');
SET @sql := IF(@ok_d1343 = 5, 'SELECT 1', 'SELECT `中止：退款状态字典 1343 不为5项`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT 'E2E-04 包B 迁移完成：ws_refund 改造 + ws_refund_event + 字典 1373 + 1343 补 4/5' AS RESULT;

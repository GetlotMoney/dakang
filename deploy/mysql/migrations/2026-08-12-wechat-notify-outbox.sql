-- ============================================================
-- 微信订阅通知 outbox（WX-ECO S2，2026-08-12）
--
-- 任务书：「业务事务只登记 outbox；Worker 在提交后发送」「以业务对象+事件类型作幂等键，
-- 支持重试、退避和人工状态」「模板 ID 只从环境配置读取；缺失时不发送、不伪造、不影响主业务」。
--
-- 【为什么不复用 ws_domain_event】S0 审计查明它不是可用 outbox：CONSUMED_FLAG 落库恒置 NO、
-- 全仓无任何组件读取或翻转它、该列也没有索引，更没有认领/租约/重试/退避/人工态。
-- 它的 DDL 注释却写着「后续消息中心/微信订阅通知从此表消费」——那条注释会把人引向
-- 一个不存在的地基。本迁移顺带把那句话改成事实（见下方 ALTER COMMENT）。
--
-- 【为什么不复用 ws_message】它是「已投递事实表」不是待发队列：SEND_STATUS 的
-- 1待发送/2发送中/3发送失败三个态在生产从未被写入，落库恒为 4已送达；且全表没有
-- 任何业务幂等键与唯一约束——在它前面加重试 Worker，同一动作重放会插出第二条第三条，
-- 数据库层没有任何东西能拦住。
--
-- 【为什么状态字典复用 1408 而不是新铸一个】仓里已有两套 outbox 状态口径
-- （1408 五态带租约退避 / 1387 三态无租约）。再开第三套的代价是排障时无法凭状态值
-- 判断该看哪张表。1408 的五个标签（待处理/处理中/已处理/待重试/需人工）本身与域无关，
-- 其 DDL 自述也已是「共用值域」，故直接沿用同一套数值与语义，一个新号都不加。
--
-- 【为什么另建表而不是往物流 outbox 里塞】两者的重试代价与人工处置方式完全不同：
-- 物流动作失败要联系承运商，订阅通知失败通常是用户没授权或模板停用。混在一张表里，
-- 运维筛「需人工」会得到两类根本不同的待办。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + 先探再改注释；重复执行零变更。
-- 非破坏：只有建表与 MODIFY COLUMN 注释（不改类型、不改可空性、不动数据）。
-- ============================================================

SET NAMES utf8mb4;

-- 前置守卫：存量同名表若缺幂等唯一键，"同一业务对象同一事件只发一次"的地基就是空的
SET @tbl := (SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name = 'ws_wechat_notify_outbox');
SET @uk := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ws_wechat_notify_outbox'
              AND index_name = 'uk_wx_notify_key' AND non_unique = 0);
SET @sql := IF(@tbl = 0 OR @uk > 0, 'SELECT 1',
  'SELECT `中止：存量 ws_wechat_notify_outbox 缺 uk_wx_notify_key 唯一键`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

CREATE TABLE IF NOT EXISTS `ws_wechat_notify_outbox` (
  `ID`                bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`       tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`         bigint       NOT NULL COMMENT '创建人ID（业务事务登记，系统恒0）',
  `CREATE_TIME`       varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`         bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`       varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `EVENT_TYPE`        varchar(40)  NOT NULL COMMENT '事件类型，见 WechatNotifyEnum.EventType（首批七类）',
  `BIZ_OBJECT_TYPE`   varchar(24)  NOT NULL COMMENT '业务对象类型：ORDER/TASK/MALL_ORDER/AFTER_SALE/REFILL_RULE 等',
  `BIZ_OBJECT_NO`     varchar(64)  NOT NULL COMMENT '业务对象编号（订单号/任务号等），排障锚点',
  `BIZ_NOTIFY_KEY`    varchar(140) NOT NULL COMMENT '幂等键 WXN:<事件类型>:<对象类型>:<对象编号>；同一动作恒一行',
  `RECEIVER_USER_ID`  bigint       NOT NULL COMMENT '收件人(ws_user.ID)；openid 由 Worker 发送时按 ID 现查，绝不落库',
  `PAYLOAD_SNAP`      text         COMMENT '模板数据快照（业务事务内冻结）：Worker 发送时不再反查业务表，避免读到已变化的状态',
  `PROCESSING_STATUS` tinyint      NOT NULL COMMENT '处理状态(1408，与商城物流 outbox 共用值域)：1待处理 2处理中 3已处理 4待重试 5需人工',
  `RETRY_COUNT`       int          NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `NEXT_RETRY_TIME`   varchar(14)  DEFAULT NULL COMMENT '下次可重试时间（退避）',
  `CLAIM_TIME`        varchar(14)  DEFAULT NULL COMMENT '认领时间',
  `LEASE_UNTIL`       varchar(14)  DEFAULT NULL COMMENT '租约到期；过期后可被重新认领，防进程崩溃后永久卡在处理中',
  `SKIP_REASON`       varchar(40)  DEFAULT NULL COMMENT '未发送原因：TEMPLATE_UNCONFIGURED模板未配 / NO_SUBSCRIPTION用户无授权额度。已处理但没发出去，与发送成功必须可区分',
  `LAST_ERROR`        varchar(500) DEFAULT NULL COMMENT '最近一次失败原因',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_wx_notify_key` (`BIZ_NOTIFY_KEY`),
  KEY `idx_wx_notify_status` (`PROCESSING_STATUS`, `NEXT_RETRY_TIME`),
  KEY `idx_wx_notify_receiver` (`RECEIVER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='微信订阅通知出站队列：业务事务只登记，Worker 提交后发送，绝不在事务内外呼';

-- 不插任何种子：待发通知是运行时事实。

-- ---------- 顺带修正一条会误导人的注释 ----------
-- ws_domain_event.CONSUMED_FLAG 的原注释宣称「后续消息中心/微信订阅通知从此表消费」，
-- 而该列从未被任何代码读取或翻转、也无索引。S2 已确认不走这条路，把注释改回事实，
-- 否则下一个人还会照着它去建一个不存在的消费者。
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ws_domain_event'
               AND column_name = 'CONSUMED_FLAG');
SET @sql := IF(@col = 1,
  'ALTER TABLE ws_domain_event MODIFY COLUMN `CONSUMED_FLAG` tinyint NOT NULL DEFAULT 1 COMMENT ''是否已被通知消费(1)：1否 2是。当前无任何消费者：全仓无代码读取或翻转本列，该列也无索引；微信订阅通知走 ws_wechat_notify_outbox，不从本表消费''',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 终检
SET @final := (SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'ws_wechat_notify_outbox')
            + (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ws_wechat_notify_outbox'
                 AND index_name = 'uk_wx_notify_key' AND non_unique = 0);
SET @sql := IF(@final = 2, 'SELECT ''微信订阅通知 outbox 迁移完成''', 'SELECT `中止：终检失败`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

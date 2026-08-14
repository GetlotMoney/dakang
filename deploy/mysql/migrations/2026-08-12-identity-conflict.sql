-- ============================================================
-- 账号身份冲突台账（WX-ECO S1，2026-08-12）
--
-- 任务书要求：「禁止自动合并账号。冲突必须 fail-closed，并进入**可审计的人工处理状态**」。
-- 现状是前半句已做到（四类冲突全部抛业务异常、零副作用），后半句完全没有：
-- 冲突发生后除了给用户一句「请联系客服处理」，系统里不留任何痕迹。客服接到电话时
-- 手上没有任何信息——不知道是哪两个账号撞了、撞了几次、之前有没有人处理过。
--
-- 【为什么不复用 ws_domain_event】它是纯审计流水，没有处理状态位（CONSUMED_FLAG 是 n8n
-- 消费标记，全仓从未被翻转）。「人工处理状态」要求的是能被认领、能标记已处理的台账。
--
-- 【为什么不做成第三套 outbox】它没有 Worker、没有租约、没有重试、没有退避——
-- 冲突不需要被"投递"，需要的是被人看见并逐条裁决。仓里已有两套 outbox 状态字典
-- （1408 五态带租约 / 1387 三态），再开第三套会让排障时无法凭状态值判断该看哪张表。
--
-- 【为什么不存 openid】冲突用「哪两个账号」就能完整表达，运营拿 userId 能查到其余一切。
-- 把身份密钥复制进第二张表只会扩大泄漏面，而对处置没有任何帮助。手机号只存脱敏值，
-- 同理：人工核对够用，泄漏面不扩大。
--
-- 【为什么 ACTOR_USER_ID 用 0 哨兵而不是 NULL】票据路径的冲突发生在建号之前，发起方
-- 还没有账号。MySQL 唯一键忽略 NULL——用 NULL 会让同一冲突每次重试都插一行新的，
-- 幂等键形同虚设（ws_split_component 的 RECEIVER_USER_ID 已因同一原因用 0 哨兵，
-- 其 DDL 注释写着「NULL不参与唯一约束，沿用V1教训」）。
--
-- 【为什么累加次数而不是插新行】被拒的用户会反复重试，每次插行会让台账被同一件事淹掉，
-- 真正需要处理的其他冲突反而看不见。
--
-- 幂等：CREATE TABLE IF NOT EXISTS，重复执行零变更。
-- 非破坏：只有建表。
-- ============================================================

SET NAMES utf8mb4;

-- 前置守卫：存量同名表若缺幂等唯一键，"同一冲突只有一行"这条不变式的地基就是空的
SET @tbl := (SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name = 'ws_identity_conflict');
-- COUNT(DISTINCT index_name)：statistics 对复合索引每列一行；此处虽是单列键，
-- 仍按去重写法，避免将来把键改成复合时这条守卫静默失准。
SET @uk := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ws_identity_conflict'
              AND index_name = 'uk_identity_conflict_key' AND non_unique = 0);
SET @sql := IF(@tbl = 0 OR @uk > 0, 'SELECT 1',
  'SELECT `中止：存量 ws_identity_conflict 缺 uk_identity_conflict_key 唯一键`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

CREATE TABLE IF NOT EXISTS `ws_identity_conflict` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID（系统留痕恒为0）',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `CONFLICT_KEY`     varchar(120) NOT NULL COMMENT '幂等键 IDC:<类型>:<持有方>:<发起方|0>:<脱敏号>；同一冲突恒一行',
  `CONFLICT_TYPE`    varchar(40)  NOT NULL COMMENT '冲突类型，见 MiniIdentityConflictEnum.Type',
  `OCCUR_SCENE`      varchar(24)  NOT NULL COMMENT '发生场景：LOGIN_BIND登录绑定链 / SELF_BIND已登录自助补绑',
  `HOLDER_USER_ID`   bigint       NOT NULL COMMENT '当前持有该身份的账号(ws_user.ID)——冲突的另一方',
  `ACTOR_USER_ID`    bigint       NOT NULL COMMENT '发起动作的账号(ws_user.ID)；票据路径建号前用0哨兵（NULL不参与唯一约束）',
  `MASKED_PHONE`     varchar(20)  DEFAULT NULL COMMENT '脱敏手机号，仅供人工核对；绝不存明文，更不存 openid',
  `OCCUR_COUNT`      int          NOT NULL DEFAULT 1 COMMENT '同一冲突累计发生次数；用户反复重试只累加不新增行',
  `FIRST_OCCUR_TIME` varchar(14)  NOT NULL COMMENT '首次发生时间',
  `LAST_OCCUR_TIME`  varchar(14)  NOT NULL COMMENT '最近发生时间',
  `HANDLE_STATUS`    tinyint      NOT NULL DEFAULT 1 COMMENT '处理状态：1待处理 2已处理 3已忽略',
  `HANDLE_BY`        bigint       DEFAULT NULL COMMENT '处理人(api_employee.ID)',
  `HANDLE_TIME`      varchar(14)  DEFAULT NULL COMMENT '处理时间',
  `HANDLE_REMARK`    varchar(500) DEFAULT NULL COMMENT '处理说明：怎么裁决的、通知了谁',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_identity_conflict_key` (`CONFLICT_KEY`),
  KEY `idx_identity_conflict_pending` (`HANDLE_STATUS`, `LAST_OCCUR_TIME`),
  KEY `idx_identity_conflict_holder` (`HOLDER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='账号身份冲突台账：fail-closed 后的人工处理面，不是队列也不是 outbox';

-- 不插任何种子：冲突是运行时事实，种子只会让台账一上来就带着假待办。

-- 终检
SET @final := (SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'ws_identity_conflict')
            + (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ws_identity_conflict'
                 AND index_name = 'uk_identity_conflict_key' AND non_unique = 0);
SET @sql := IF(@final = 2, 'SELECT ''身份冲突台账迁移完成''', 'SELECT `中止：终检失败`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

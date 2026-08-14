-- ============================================================
-- 分润六方口径落地（D-428，2026-08-14）：归属载体两表 + 分润归属页与功能点
--
-- 甲方 2026-08-14 分配表（合计恰 100%）：商务推广5 / 水站50 / 运营中心5（省市区县
-- 级差，8.6 会议口径省5市3区县2）/ 市场激励5 / 公司运营15 / 设备20。后三方为公司
-- 内部记账科目，由平台余数行整块承接（40%），不伪造收款人。比例本身不在本迁移：
-- 整版计划由管理端 /finance/plan/create 发布（种子禁插计划，SplitV2GuardrailTest 看守）。
--
-- 结构：前置守卫（纯读）→ 单事务写入段 → 事务外终检。中止即客户端停在错误语句，
-- 未提交事务随连接断开整体回滚。幂等：建表 IF NOT EXISTS；菜单按主键 INSERT IGNORE
-- （守卫一保证撞行必是本迁移自己的行）；绑定先复活墓碑再 NOT EXISTS 补缺。
-- 非破坏：只建表、插行、复活墓碑，无删无改列。
-- ============================================================

SET NAMES utf8mb4;

-- ---------- 守卫一：菜单号段占用核对 ----------
-- 1053（分润归属页）与 1167/1168（功能点）已存在时必须逐一与本迁移相符，否则中止。
-- 比较必须空安全（<=>）：外来占号行的 MENU_PATH/MENU_API_PERMS 常有一列为 NULL，
-- 普通等号在 NULL 上求值为 NULL 被 WHERE 滤掉，守卫会对最典型的占号形态失明
SET @bad := (SELECT COUNT(*) FROM `api_rbac_menu` m
             JOIN (
               SELECT 1053 AS id, 'attribution' AS mark UNION ALL
               SELECT 1167, 'finance:attribution:query' UNION ALL
               SELECT 1168, 'finance:attribution:edit'
             ) e ON m.`ID` = e.id
             WHERE NOT (m.`MENU_PATH` <=> e.mark OR m.`MENU_API_PERMS` <=> e.mark));
SET @sql := IF(@bad = 0, 'SELECT 1',
  'SELECT `中止：1053/1167/1168 号段存在不符的占用行，须先迁走占号菜单再执行`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 守卫二：父挂点存活（1004 订单中心一级入口） ----------
SET @parents := (SELECT COUNT(*) FROM `api_rbac_menu` WHERE `ID` = 1004 AND `DATA_STATUS` = 0);
SET @sql := IF(@parents = 1, 'SELECT 1', 'SELECT `中止：订单中心一级菜单缺失或已删`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 归属载体两表（与 init/领域源三轨逐字一致） ----------
CREATE TABLE IF NOT EXISTS `ws_owner_referrer` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `OWNER_USER_ID`    bigint       NOT NULL COMMENT '机主用户ID(ws_user.ID)；一人一行终身冻结',
  `REFERRER_USER_ID` bigint       NOT NULL COMMENT '直接推荐人用户ID(ws_user.ID)；任何身份可担任，仅此一级(D-404)',
  `BIND_SOURCE`      varchar(20)  NOT NULL COMMENT '建立来源：ADMIN_ENTRY后台录入/INVITE_LINK邀请链路(预留)',
  `BIND_TIME`        varchar(14)  NOT NULL COMMENT '建立时点；建立即冻结(D-406)，换绑走申诉流程(REQ-058未开放)',
  `REFERRER_REMARK`  varchar(200) DEFAULT NULL COMMENT '备注(max200)',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_owner_referrer_owner` (`OWNER_USER_ID`),
  KEY `idx_owner_referrer_ref` (`REFERRER_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='机主加盟推荐关系：谁招来的这个机主；与用户邀请链物理分列(D-404)，一人一行建立即冻结';

CREATE TABLE IF NOT EXISTS `ws_owner_attribution` (
  `ID`                     bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`            tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`              bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`            varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`              bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`            varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `OWNER_USER_ID`          bigint       NOT NULL COMMENT '机主用户ID(ws_user.ID)；一人一行建立即冻结',
  `ATTRIBUTION_SOURCE`     varchar(20)  NOT NULL COMMENT '归属来源：PRIVATE_REFERRAL血缘/PUBLIC_MANUAL公域人工(D-407)；无行=公域未分配',
  `PROVINCE_AGENT_USER_ID` bigint       DEFAULT NULL COMMENT '省级运营中心用户ID；空=该级无人(缺席级差切片归最近在场上级，D-428)',
  `CITY_AGENT_USER_ID`     bigint       DEFAULT NULL COMMENT '市级运营中心用户ID；空=该级无人',
  `COUNTY_AGENT_USER_ID`   bigint       DEFAULT NULL COMMENT '区县级运营中心用户ID；空=该级无人',
  `BIND_TIME`              varchar(14)  NOT NULL COMMENT '建立时点；建立即冻结(D-406)，跨区放机不改归属',
  `ATTRIBUTION_REMARK`     varchar(200) DEFAULT NULL COMMENT '备注(max200)：分配依据、合同号等',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_owner_attribution_owner` (`OWNER_USER_ID`),
  KEY `idx_owner_attr_province` (`PROVINCE_AGENT_USER_ID`),
  KEY `idx_owner_attr_city` (`CITY_AGENT_USER_ID`),
  KEY `idx_owner_attr_county` (`COUNTY_AGENT_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='机主区域归属链：省市区县运营中心各是谁；血缘冻结不按地缘(D-406)，无行=公域未分配';

-- ============================================================
-- 写入段：单事务（全为 DML；中止即整体回滚不留半态）
-- ============================================================
START TRANSACTION;

INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`) VALUES
(1053,'分润归属',2,NULL,1004,0,'attribution','/order/attribution',1,NULL,NULL,1,1,0,1,'20260814120000',1,'20260814120000'),
(1167,'查询分润归属',3,NULL,1053,1,NULL,NULL,1,'finance:attribution:query','finance:attribution:query',2,1,0,1,'20260814120000',1,'20260814120000'),
(1168,'录入分润归属',3,NULL,1053,2,NULL,NULL,1,'finance:attribution:edit','finance:attribution:edit',2,1,0,1,'20260814120000',1,'20260814120000');

-- 自有行复活（守卫一保证号段内只有自己的行）
UPDATE `api_rbac_menu`
SET `DATA_STATUS` = 0, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260814120000'
WHERE `ID` IN (1053,1167,1168) AND `DATA_STATUS` <> 0;

-- 超管绑定：先复活墓碑再补缺行（api_rbac_role_menu 除主键外无唯一键，幂等靠 NOT EXISTS）
UPDATE `api_rbac_role_menu`
SET `DATA_STATUS` = 0, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260814120000'
WHERE `ROLE_ID` = 1 AND `MENU_ID` IN (1053,1167,1168) AND `DATA_STATUS` <> 0;

INSERT INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260814120000',1,'20260814120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` IN (1053,1167,1168)
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

COMMIT;

-- ============================================================
-- 终检（事务外，纯读）：语义核对而非行数计数
-- ============================================================
SET @tbl := (SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = DATABASE()
               AND table_name IN ('ws_owner_referrer','ws_owner_attribution'))
          + (SELECT COUNT(DISTINCT table_name) FROM information_schema.statistics
             WHERE table_schema = DATABASE()
               AND ((table_name = 'ws_owner_referrer' AND index_name = 'uk_owner_referrer_owner' AND non_unique = 0)
                 OR (table_name = 'ws_owner_attribution' AND index_name = 'uk_owner_attribution_owner' AND non_unique = 0)));
SET @menu := (SELECT COUNT(*) FROM `api_rbac_menu`
              WHERE `ID` = 1053 AND `MENU_PATH` = 'attribution' AND `DATA_STATUS` = 0)
           + (SELECT COUNT(*) FROM `api_rbac_menu` m
              JOIN (SELECT 1167 AS id, 'finance:attribution:query' AS perms
                    UNION ALL SELECT 1168, 'finance:attribution:edit') e ON m.`ID` = e.id
              WHERE m.`DATA_STATUS` = 0 AND m.`MENU_API_PERMS` = e.perms AND m.`MENU_WEB_PERMS` = e.perms);
SET @bind := (SELECT COUNT(*) FROM `api_rbac_role_menu`
              WHERE `ROLE_ID` = 1 AND `MENU_ID` IN (1053,1167,1168) AND `DATA_STATUS` = 0);
SET @sql := IF(@tbl = 4 AND @menu = 3 AND @bind = 3,
  'SELECT ''分润六方归属载体与功能点就绪''',
  'SELECT `中止：终检未达成（表/唯一键/菜单/绑定存在缺口）`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

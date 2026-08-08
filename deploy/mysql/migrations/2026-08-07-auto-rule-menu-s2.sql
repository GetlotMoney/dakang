-- ============================================================
-- 自动补货规则后台只读查询菜单（S2，2026-08-07）
--
-- 订单中心组新增「自动补货规则」菜单（只读：客服追踪用户规则与执行结果；
-- 启停与取消是用户自助动作，后台不代操作，故无功能点行）。
-- 菜单带显式主键 ID：INSERT IGNORE 撞主键即幂等（mysql8 skill 口径）。
-- 超管角色绑定沿用基线的全菜单 SELECT 关联（api_rbac_role_menu NOT EXISTS 写法）。
-- ============================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`)
VALUES
(1033,'自动补货规则',2,NULL,1004,0,'autorule','/order/autorule',1,NULL,NULL,1,1,0,1,'20260807120000',1,'20260807120000');

INSERT IGNORE INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260807120000',1,'20260807120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` = 1033
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

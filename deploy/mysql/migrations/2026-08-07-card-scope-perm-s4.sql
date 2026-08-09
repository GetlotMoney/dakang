-- ============================================================
-- 水卡授权范围维护功能点（S4，2026-08-07）
--
-- PC 水卡详情新增「修改授权范围」：服务端按结构化维度构造 SCOPE_JSON
-- （WaterCardScope 唯一校验口），旧值 CAS 防并发覆盖，@LogOperation 审计。
-- 菜单带显式主键 ID：INSERT IGNORE 撞主键即幂等。
-- ============================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`)
VALUES
(1149,'修改授权范围',3,NULL,1041,3,NULL,NULL,1,'user:card:scope','user:card:scope',2,1,0,1,'20260807120000',1,'20260807120000');

INSERT IGNORE INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260807120000',1,'20260807120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` = 1149
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

-- ============================================================
-- 水种用量统计菜单（S5，2026-08-07）
--
-- 订单中心组新增「水种用量统计」只读页：取水计划/实际/退差、配送桶数与折算
-- 水量、异常单数，按水种/水站/时间筛选。统计只聚合现有订单与任务事实，
-- 不建第二套业务账本；工厂生产量无权威数据源，页面显式标注未提供。
-- ============================================================

SET NAMES utf8mb4;

INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`)
VALUES
(1034,'水种用量统计',2,NULL,1004,0,'waterstats','/order/waterstats',1,NULL,NULL,1,1,0,1,'20260807120000',1,'20260807120000');

INSERT IGNORE INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260807120000',1,'20260807120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` = 1034
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

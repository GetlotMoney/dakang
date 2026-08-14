-- ============================================================
-- 菜单显示名收口（2026-08-14）：面向运营的名称去行业黑话、消同页两名
--
-- 1040 「C端用户」→「用户列表」：一级已是「用户管理」，页签讲职责；
--   "C端"是行业内部用语，不是后台运营的语言。
-- 1051 「配送履约」→「配送任务」：同一页面在主库菜单与页内导航叫两个名，
--   统一为页内导航既用的「配送任务」（与商城的「商城履约」也不再撞语义）。
--
-- 只改显示名：ID/路径/组件/权限码零变更，深链与 RBAC 不受影响。
-- 幂等：UPDATE 到目标值，重复执行零变更。与 init/03 同轨。
-- ============================================================

SET NAMES utf8mb4;

UPDATE `api_rbac_menu`
SET `MENU_NAME` = '用户列表', `UPDATE_BY` = 1, `UPDATE_TIME` = '20260814200000'
WHERE `ID` = 1040 AND `MENU_NAME` <> '用户列表';

UPDATE `api_rbac_menu`
SET `MENU_NAME` = '配送任务', `UPDATE_BY` = 1, `UPDATE_TIME` = '20260814200000'
WHERE `ID` = 1051 AND `MENU_NAME` <> '配送任务';

-- 终检
SET @final := (SELECT COUNT(*) FROM `api_rbac_menu` WHERE `ID` = 1040 AND `MENU_NAME` = '用户列表')
            + (SELECT COUNT(*) FROM `api_rbac_menu` WHERE `ID` = 1051 AND `MENU_NAME` = '配送任务');
SET @sql := IF(@final = 2, 'SELECT ''菜单显示名收口完成''', 'SELECT `中止：菜单名未达目标`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

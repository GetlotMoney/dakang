-- ============================================================
-- E2E-07 消息、告警与工单 包A：PC「消息记录」菜单（幂等迁移，存量库升级用）
--
-- 本迁移只做一件事：为存量库补菜单 1027（消息记录，挂 1003 用户管理）与超管角色绑定。
-- 新库由 03-demo-baseline.sql 直接得到同一行（双份铁律：init 基线 + migrations 升级）。
-- 本链无新表、无新列、无新字典、无索引——消息读端全部复用 E2E-03 已建的 ws_message。
--
-- 幂等：INSERT IGNORE + 绑定 NOT EXISTS；重复执行零变化。
-- ============================================================

SET NAMES utf8mb4;

-- 前置：菜单与绑定两表必须存在
SET @t_menu := (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'api_rbac_menu');
SET @sql := IF(@t_menu = 1, 'SELECT 1', 'SELECT `中止：api_rbac_menu 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @t_bind := (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'api_rbac_role_menu');
SET @sql := IF(@t_bind = 1, 'SELECT 1', 'SELECT `中止：api_rbac_role_menu 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 前置：父菜单 1003（用户管理）必须在位——否则会插出导航不可达的孤儿菜单
SET @m_parent := (SELECT COUNT(*) FROM api_rbac_menu WHERE ID = 1003 AND DATA_STATUS = 0);
SET @sql := IF(@m_parent = 1, 'SELECT 1', 'SELECT `中止：父菜单 1003 用户管理不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 前置：固定 ID 1027 不得被异物占用（同名视为已完成走幂等；异名说明 ID 被其它功能挪用，
-- INSERT IGNORE 会静默跳过、后置不变式却照样通过——必须在这里显式中止，防静默成功型失败）
SET @m_taken := (SELECT COUNT(*) FROM api_rbac_menu WHERE ID = 1027 AND MENU_NAME <> '消息记录');
SET @sql := IF(@m_taken = 0, 'SELECT 1', 'SELECT `中止：菜单ID 1027 已被其它功能占用`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 菜单（列清单与 03-demo-baseline 逐列一致；插入+复活+绑定，同 E2E-05 迁移姿势）
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,
 `MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,
 `DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`) VALUES
(1027,'消息记录',2,NULL,1003,0,'message','/user/message',1,NULL,NULL,1,1,0,1,'20260731120000',1,'20260731120000');

-- 曾被逻辑删除的绑定先复活（NOT EXISTS 只看有没有行，留 DATA_STATUS=1 的旧绑定会让插入被跳过）
UPDATE `api_rbac_role_menu`
SET `DATA_STATUS` = 0, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260731120000'
WHERE `ROLE_ID` = 1 AND `MENU_ID` = 1027 AND `DATA_STATUS` <> 0;

INSERT INTO `api_rbac_role_menu`
(`DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `ROLE_ID`, `MENU_ID`)
SELECT 0, 1, '20260731120000', 1, '20260731120000', 1, m.ID
FROM api_rbac_menu m
WHERE m.ID = 1027
  AND NOT EXISTS (SELECT 1 FROM api_rbac_role_menu rm WHERE rm.ROLE_ID = 1 AND rm.MENU_ID = m.ID);

-- 后置不变式：菜单与绑定必须都在
SET @ok := (SELECT COUNT(*) FROM api_rbac_menu WHERE ID = 1027 AND DATA_STATUS = 0)
         + (SELECT COUNT(*) FROM api_rbac_role_menu WHERE ROLE_ID = 1 AND MENU_ID = 1027 AND DATA_STATUS = 0);
SET @sql := IF(@ok = 2, 'SELECT ''E2E-07 包A 迁移完成：PC 消息记录菜单与角色绑定'' AS RESULT',
  'SELECT `中止：菜单 1027 或角色绑定缺失`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

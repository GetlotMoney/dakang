-- ============================================================
-- 六维达康 · 超管角色全菜单补绑（幂等，可反复执行）
-- 用途：每次新模块 SQL 执行完后运行一次，把新增菜单自动授权给超管（ROLE_ID=1）；
--      同时纠正历史绑定的逻辑删除位（种子数据曾出现 DATA_STATUS=1 导致登录菜单为空）。
-- 执行：mysql -h127.0.0.1 -P3308 -u"$DAKANG_DB_USERNAME" -p"$DAKANG_DB_PASSWORD" --default-character-set=utf8mb4 dakang < grant_super.sql
-- ============================================================

UPDATE api_rbac_role_menu SET DATA_STATUS = 0 WHERE ROLE_ID = 1 AND DATA_STATUS <> 0;

INSERT IGNORE INTO api_rbac_role_menu (CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME, DATA_STATUS, ROLE_ID, MENU_ID)
SELECT 1, '20260711120000', 1, '20260711120000', 0, 1, m.ID
FROM api_rbac_menu m
WHERE m.DATA_STATUS = 0
  AND NOT EXISTS (
    SELECT 1 FROM api_rbac_role_menu rm WHERE rm.ROLE_ID = 1 AND rm.MENU_ID = m.ID
  );

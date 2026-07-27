-- ============================================================
-- 六维达康 · 套餐管理功能点补齐（P1-04 权限部分，2026-07-19）
-- 背景：套餐菜单 1031 无 type=3 功能点，admin 登录后 packagePerms 为空，
--       前端 hasPermission 门控（product:package:add/update/shelf）导致按钮全部不渲染。
-- 本文件只补权限点与超管授权，不改前端；套餐接真属主链后续任务。
-- 权限值必须与 client/src/views/product/package/index.vue 的 hasPermission 一字不差：
--   add / update / shelf（上下架）。ID 1130-1135 已被占用，选用 1136/1137/1138。
-- 执行：mysql -h127.0.0.1 -P3308 -uroot -p"$DAKANG_DB_PASSWORD" dakang < server/sql/package-perms.sql
-- 口令不入库：先 `set -a; . .env; set +a` 导出仓库根 .env 的 DAKANG_DB_PASSWORD（模板见 .env.example）
-- 镜像：deploy/mysql/init/03-demo-baseline.sql 菜单段同步追加同样三行（双写防漂移）。
-- ============================================================
SET NAMES utf8mb4;
USE dakang;

-- 套餐管理（1031）三个功能点，格式参照水种 1120-1122
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`) VALUES
(1136,'新增套餐',3,NULL,1031,1,NULL,NULL,1,'product:package:add','product:package:add',2,1,0,1,'20260719120000',1,'20260719120000'),
(1137,'修改套餐',3,NULL,1031,2,NULL,NULL,1,'product:package:update','product:package:update',2,1,0,1,'20260719120000',1,'20260719120000'),
(1138,'套餐上下架',3,NULL,1031,3,NULL,NULL,1,'product:package:shelf','product:package:shelf',2,1,0,1,'20260719120000',1,'20260719120000');

-- 超级管理员（ROLE_ID=1）授权三个新功能点；NOT EXISTS 保证重复执行幂等
INSERT IGNORE INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260719120000',1,'20260719120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` IN (1136,1137,1138)
  AND m.`DATA_STATUS` = 0
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

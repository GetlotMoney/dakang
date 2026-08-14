-- ============================================================
-- 同系后台取长补短：用户档案/商城写动作/财务只读功能点 + 存量权限修正（2026-08-13）
--
-- 运行时权限 = 角色已授菜单行 MENU_API_PERMS 的并集（ApiRbacRoleServiceImpl.getUserPermission，
-- 无超管旁路）。本轮后端给用户档案端点、商城售后/履约写端点、财务三个只读端点补了
-- @SaCheckPermission，功能点未落库前连超管都会 403——SQL 必须与后端 jar 同轮上线。
--
-- 结构：前置守卫（纯读，任何写入之前）→ 单事务写入段 → 事务外终检。
-- 中止即 mysql 客户端停在错误语句上，未提交事务随连接断开整体回滚，不留半态。
-- 幂等：api_rbac_menu 按主键 INSERT IGNORE（守卫一保证撞行必是本迁移自己的行）；
-- api_rbac_role_menu 除主键外无唯一键，幂等靠「先复活墓碑，再 NOT EXISTS 补缺」。
-- 非破坏：只插行、复活墓碑、清 616/617 页面权限码、逻辑退役 1134，无删无 DDL。
-- ============================================================

SET NAMES utf8mb4;

-- ---------- 守卫一：号段占用核对 ----------
-- api_rbac_menu 主键 IdType.AUTO，主库自增此刻恰好推进到 1156；GUI「菜单管理」新建会顺号
-- 占用本迁移硬编码的 1156~1166。被占后 INSERT IGNORE 静默跳过，后续绑定拿到的是占号行的
-- 权限码，日志搬迁会反向执行成权限剥夺。故任何写入前先断言：号段内已存在的行必须逐一
-- 与本迁移的权限码相符（即上次执行留下的自己的行），否则中止。
SET @bad := (SELECT COUNT(*) FROM `api_rbac_menu` m
             JOIN (
               SELECT 1156 AS id, 'user:user:query' AS perms
               UNION ALL SELECT 1157, 'mall:aftersale:audit'
               UNION ALL SELECT 1158, 'mall:aftersale:handle'
               UNION ALL SELECT 1159, 'mall:aftersale:refund'
               UNION ALL SELECT 1160, 'mall:fulfillment:handle'
               UNION ALL SELECT 1161, 'mall:fulfillment:dispatch'
               UNION ALL SELECT 1162, 'api:logLogin:query'
               UNION ALL SELECT 1163, 'api:logOperation:query'
               UNION ALL SELECT 1164, 'finance:split:query'
               UNION ALL SELECT 1165, 'finance:reconcile:query'
               UNION ALL SELECT 1166, 'finance:config:query'
             ) e ON m.`ID` = e.id
             WHERE m.`MENU_API_PERMS` IS NULL OR m.`MENU_API_PERMS` <> e.perms);
SET @sql := IF(@bad = 0, 'SELECT 1',
  'SELECT `中止：1156~1166 号段存在权限码不符的占用行，须先迁走占号菜单再执行`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 守卫二：父挂点与搬迁对象存活 ----------
-- 功能点父菜单（1040 用户管理页 / 1044 商城履约页 / 1045 商城售后页 / 1028 分账明细页 /
-- 1029 日对账页 / 1032 比例配置页）与日志两页（616/617，MENU_TYPE=2）共 8 行必须在册且未删，
-- 否则新功能点是授权弹窗里点不到的孤儿。
SET @parents := (SELECT COUNT(*) FROM `api_rbac_menu`
                 WHERE `ID` IN (1040,1044,1045,1028,1029,1032) AND `DATA_STATUS` = 0)
              + (SELECT COUNT(*) FROM `api_rbac_menu`
                 WHERE `ID` IN (616,617) AND `MENU_TYPE` = 2 AND `DATA_STATUS` = 0);
SET @sql := IF(@parents = 8, 'SELECT 1',
  'SELECT `中止：功能点父菜单或日志页面缺失/已删`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============================================================
-- 写入段：单事务。全部为 DML 与 SELECT 型 PREPARE，无隐式提交语句；
-- 任一断言中止都会让整段回滚，「先补授后清空」的顺序担保由此升级为结构担保。
-- ============================================================
START TRANSACTION;

-- 十一个功能点（守卫一已保证撞行即本迁移自己的行，IGNORE 幂等成立）
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`) VALUES
(1156,'查看用户档案',3,NULL,1040,1,NULL,NULL,1,'user:user:query','user:user:query',2,1,0,1,'20260813120000',1,'20260813120000'),
(1157,'售后审核',3,NULL,1045,1,NULL,NULL,1,'mall:aftersale:audit','mall:aftersale:audit',2,1,0,1,'20260813120000',1,'20260813120000'),
(1158,'售后收货与质检',3,NULL,1045,2,NULL,NULL,1,'mall:aftersale:handle','mall:aftersale:handle',2,1,0,1,'20260813120000',1,'20260813120000'),
(1159,'售后模拟退款',3,NULL,1045,3,NULL,NULL,1,'mall:aftersale:refund','mall:aftersale:refund',2,1,0,1,'20260813120000',1,'20260813120000'),
(1160,'前置仓拣货打包',3,NULL,1044,1,NULL,NULL,1,'mall:fulfillment:handle','mall:fulfillment:handle',2,1,0,1,'20260813120000',1,'20260813120000'),
(1161,'安排发运',3,NULL,1044,2,NULL,NULL,1,'mall:fulfillment:dispatch','mall:fulfillment:dispatch',2,1,0,1,'20260813120000',1,'20260813120000'),
(1162,'查询登录日志',3,NULL,616,1,NULL,NULL,1,'api:logLogin:query','api:logLogin:query',2,1,0,1,'20260813120000',1,'20260813120000'),
(1163,'查询操作日志',3,NULL,617,1,NULL,NULL,1,'api:logOperation:query','api:logOperation:query',2,1,0,1,'20260813120000',1,'20260813120000'),
(1164,'查询分账明细',3,NULL,1028,2,NULL,NULL,1,'finance:split:query','finance:split:query',2,1,0,1,'20260813120000',1,'20260813120000'),
(1165,'查询日对账',3,NULL,1029,2,NULL,NULL,1,'finance:reconcile:query','finance:reconcile:query',2,1,0,1,'20260813120000',1,'20260813120000'),
(1166,'查询比例配置',3,NULL,1032,2,NULL,NULL,1,'finance:config:query','finance:config:query',2,1,0,1,'20260813120000',1,'20260813120000');

-- 上次执行后被软删的自有功能点复活（守卫一保证号段内只有自己的行）
UPDATE `api_rbac_menu`
SET `DATA_STATUS` = 0, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260813120000'
WHERE `ID` BETWEEN 1156 AND 1166 AND `DATA_STATUS` <> 0;

-- 超管绑定：先复活墓碑再补缺行。api_rbac_role_menu 的 DATA_STATUS 带 @TableLogic，
-- 授权弹窗保存走逻辑删，NOT EXISTS 不带状态会把墓碑当「已存在」跳过——先复活即消除该盲区。
UPDATE `api_rbac_role_menu`
SET `DATA_STATUS` = 0, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260813120000'
WHERE `ROLE_ID` = 1 AND `MENU_ID` BETWEEN 1156 AND 1166 AND `DATA_STATUS` <> 0;

INSERT INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260813120000',1,'20260813120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` BETWEEN 1156 AND 1166
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

-- ============================================================
-- 存量权限修正（S4 交付 1「@SaCheckPermission 与菜单功能点逐项对应」的欠账，本轮补做）
--
-- 修正一｜提权链：/api/rbacRole/updateMenu 与 /addEmployeeToRoleList 此前都只校验 api:role:update
-- （菜单 631「修改角色」）。前者 delByRole + saveBatchByRole 整体重写角色的菜单绑定，后者把人塞进角色，
-- 服务层既不限定目标角色是否为调用者自身，也没有「只能授出自己已有权限」的天花板；运行时权限又等于
-- 角色已授菜单行 MENU_API_PERMS 的并集（无超管旁路）。于是「能改角色名」= 一次请求给自己勾满全部功能点。
-- 本轮后端改判 api:role:permission（646「分配权限」）与 api:employee:assignRole（645「分配角色」)——
-- 这两行 01-base.sql 里本就存在、前端按钮也一直按它们显示，只是后端从未引用。老库若在历次改权中
-- 丢过这两行绑定（授权弹窗保存走逻辑删，丢失形态是墓碑而非缺行），超管会在这两个入口上 403 且无法
-- 从 GUI 自救，故先复活墓碑再按 NOT EXISTS 补缺。
--
-- 修正二｜日志两个权限码下沉为功能点：api:logLogin:query / api:logOperation:query 原先挂在 616/617
-- 两行 MENU_TYPE=2 的页面菜单上。本轮授权弹窗已拆成「菜单权限 / 操作权限」两棵严格独立的树，页面树按
-- MENU_TYPE<>3 过滤并在界面上承诺「勾选菜单不会同时授予操作权限」——页面行自带 MENU_API_PERMS 会让这句话
-- 对日志两页失效。1162/1163 承接权限码后清空页面行。搬迁按权限等价做：先给所有已持有页面的角色补授对应
-- 功能点，经等价性断言确认后才清空页面行；断言失败整个事务回滚，任何角色的有效权限不变。
--
-- 修正三｜退役 device:operations:config（1134）：E2E-05 收敛已删掉 /device/operations 页上的假写动作，
-- 该页现为纯只读；全仓再无任何后端端点或前端按钮引用这个码。用 DATA_STATUS=1 逻辑退役：保留行占住 ID，
-- 不删既有角色绑定（listMenuByRoleList 过滤 DATA_STATUS=0，失效绑定不再下发）。日后该页真落地写动作时
-- 重新启用本行，不要另发新码。
-- ============================================================

-- 修正一：645 分配角色 / 646 分配权限——先复活超管墓碑，再补缺行
UPDATE `api_rbac_role_menu`
SET `DATA_STATUS` = 0, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260813120000'
WHERE `ROLE_ID` = 1 AND `MENU_ID` IN (645,646) AND `DATA_STATUS` <> 0;

INSERT INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260813120000',1,'20260813120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` IN (645,646)
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

-- 修正二之一：权限等价搬迁——凡存活持有日志页面的角色，其对应功能点绑定先复活墓碑再补缺
UPDATE `api_rbac_role_menu` t
JOIN (
    SELECT DISTINCT `ROLE_ID` AS `ROLE_ID`, 1162 AS `POINT_ID`
    FROM `api_rbac_role_menu` WHERE `MENU_ID` = 616 AND `DATA_STATUS` = 0
    UNION
    SELECT DISTINCT `ROLE_ID`, 1163
    FROM `api_rbac_role_menu` WHERE `MENU_ID` = 617 AND `DATA_STATUS` = 0
) src ON t.`ROLE_ID` = src.`ROLE_ID` AND t.`MENU_ID` = src.`POINT_ID`
SET t.`DATA_STATUS` = 0, t.`UPDATE_BY` = 1, t.`UPDATE_TIME` = '20260813120000'
WHERE t.`DATA_STATUS` <> 0;

INSERT INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260813120000',1,'20260813120000',0,src.`ROLE_ID`,src.`POINT_ID`
FROM (
    SELECT DISTINCT `ROLE_ID` AS `ROLE_ID`, 1162 AS `POINT_ID`
    FROM `api_rbac_role_menu` WHERE `MENU_ID` = 616 AND `DATA_STATUS` = 0
    UNION
    SELECT DISTINCT `ROLE_ID`, 1163
    FROM `api_rbac_role_menu` WHERE `MENU_ID` = 617 AND `DATA_STATUS` = 0
) src
WHERE NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` x
    WHERE x.`ROLE_ID` = src.`ROLE_ID` AND x.`MENU_ID` = src.`POINT_ID`
  );

-- 修正二之二：清空前的等价性断言——仍存在「存活持有日志页面、却没有存活对应功能点」的角色
-- 即中止（事务回滚，页面行不清，权限零变化）
SET @premove := (SELECT COUNT(*) FROM `api_rbac_role_menu` p
                 WHERE p.`MENU_ID` IN (616,617) AND p.`DATA_STATUS` = 0
                   AND NOT EXISTS (
                     SELECT 1 FROM `api_rbac_role_menu` q
                     WHERE q.`ROLE_ID` = p.`ROLE_ID` AND q.`DATA_STATUS` = 0
                       AND q.`MENU_ID` = CASE p.`MENU_ID` WHEN 616 THEN 1162 ELSE 1163 END
                   ));
SET @sql := IF(@premove = 0, 'SELECT 1',
  'SELECT `中止：日志权限搬迁未达等价，页面行不清空，事务回滚`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 修正二之三：清空页面行上的权限码（等价断言已过）
UPDATE `api_rbac_menu`
SET `MENU_API_PERMS` = NULL, `MENU_WEB_PERMS` = NULL, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260813120000'
WHERE `ID` IN (616,617) AND `MENU_TYPE` = 2;

-- 修正三：退役无对应动作的 device:operations:config
UPDATE `api_rbac_menu`
SET `DATA_STATUS` = 1, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260813120000'
WHERE `ID` = 1134 AND `MENU_API_PERMS` = 'device:operations:config';

COMMIT;

-- ============================================================
-- 终检（事务外，纯读）：语义核对而非行数计数——占号行/墓碑行骗不过权限码逐一比对
-- ============================================================
SET @pts := (SELECT COUNT(*) FROM `api_rbac_menu` m
             JOIN (
               SELECT 1156 AS id, 'user:user:query' AS perms
               UNION ALL SELECT 1157, 'mall:aftersale:audit'
               UNION ALL SELECT 1158, 'mall:aftersale:handle'
               UNION ALL SELECT 1159, 'mall:aftersale:refund'
               UNION ALL SELECT 1160, 'mall:fulfillment:handle'
               UNION ALL SELECT 1161, 'mall:fulfillment:dispatch'
               UNION ALL SELECT 1162, 'api:logLogin:query'
               UNION ALL SELECT 1163, 'api:logOperation:query'
               UNION ALL SELECT 1164, 'finance:split:query'
               UNION ALL SELECT 1165, 'finance:reconcile:query'
               UNION ALL SELECT 1166, 'finance:config:query'
             ) e ON m.`ID` = e.id
             WHERE m.`DATA_STATUS` = 0
               AND m.`MENU_API_PERMS` = e.perms AND m.`MENU_WEB_PERMS` = e.perms);
SET @bind := (SELECT COUNT(*) FROM `api_rbac_role_menu`
              WHERE `ROLE_ID` = 1 AND `MENU_ID` BETWEEN 1156 AND 1166 AND `DATA_STATUS` = 0)
           + (SELECT COUNT(*) FROM `api_rbac_role_menu`
              WHERE `ROLE_ID` = 1 AND `MENU_ID` IN (645,646) AND `DATA_STATUS` = 0);
SET @moved := (SELECT COUNT(*) FROM `api_rbac_menu`
               WHERE `ID` IN (616,617) AND `MENU_API_PERMS` IS NULL AND `MENU_WEB_PERMS` IS NULL)
            + (SELECT COUNT(*) FROM `api_rbac_menu` WHERE `ID` = 1134 AND `DATA_STATUS` = 1);
SET @orphan := (SELECT COUNT(*) FROM `api_rbac_role_menu` p
                WHERE p.`MENU_ID` IN (616,617) AND p.`DATA_STATUS` = 0
                  AND NOT EXISTS (
                    SELECT 1 FROM `api_rbac_role_menu` q
                    WHERE q.`ROLE_ID` = p.`ROLE_ID` AND q.`DATA_STATUS` = 0
                      AND q.`MENU_ID` = CASE p.`MENU_ID` WHEN 616 THEN 1162 ELSE 1163 END
                  ));
SET @sql := IF(@pts = 11 AND @bind = 13 AND @moved = 3 AND @orphan = 0,
  'SELECT ''取长补短功能点与存量权限修正就绪''',
  'SELECT `中止：终检未达成（功能点/绑定/搬迁/退役存在缺口）`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

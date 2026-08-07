-- E2E-04 包D-5 迁移：充值退款来源字典 + 独立财务审核权限（REQ-061）。
--
-- 【为什么要一个新的售后来源】
-- uk_after_sale_source 是按 (SOURCE_TYPE, SOURCE_ID) 唯一的。充值退款的 SOURCE_ID 是 ws_order.ID，
-- 与「取水异常核账」相同；复用同一个来源码，会让一张取水订单与一张充值订单在 ID 相同时互相占键，
-- 表现为「明明没退过款，却说售后号已存在」。故 1370 增一个取值 4=充值退款。
--
-- 【为什么要一个独立权限码】
-- 任务书 R0-7：「购卡/充值退款、外部退款必须使用独立财务审核权限」。
-- 配送售后的日常处理（order:aftersale:handle）与「把钱退回用户支付账户」不是一个风险级别，
-- 共用一个开关意味着任何能处理配送申诉的人都能发起原路退款。
--
-- 本迁移同时把售后动作 CARD_ID 放宽为可空：首次购卡未入账异常退款尚未生成水卡，
-- 生产代码仅允许 RECHARGE_REFUND + GATEWAY_REFUND 使用空值；不动任何资金数据。
-- 模式与包A/包B/包D 一致：只读检查在前、变更在后、污染即中止且零变更。

SET NAMES utf8mb4;

-- ============ 只读检查区 ============

SET @t_dict := (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('api_dict_data', 'api_rbac_menu', 'api_rbac_role_menu'));
SET @sql := IF(@t_dict = 3, 'SELECT 1', 'SELECT `中止：字典或权限依赖表不完整`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_action := (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'ws_after_sale_action');
SET @c_action_card := (SELECT COUNT(*) FROM information_schema.columns
                       WHERE table_schema = DATABASE() AND table_name = 'ws_after_sale_action'
                         AND column_name = 'CARD_ID');
SET @sql := IF(@t_action = 1 AND @c_action_card = 1, 'SELECT 1',
               'SELECT `中止：ws_after_sale_action.CARD_ID 依赖不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 前置依赖：包A 的三项来源必须各自恰好一行且语义正确；COUNT=3 不能区分「1重复三次、2/3缺失」。
SET @base_source_rows := (SELECT COUNT(*) FROM api_dict_data
                          WHERE DICT_TYPE = '1370' AND DICT_VALUE IN (1, 2, 3));
SET @base_source_exact := (SELECT COUNT(*) FROM api_dict_data
                           WHERE DICT_TYPE = '1370'
                             AND ((DICT_VALUE = 1 AND DICT_LABEL = '配送取消')
                               OR (DICT_VALUE = 2 AND DICT_LABEL = '配送申诉')
                               OR (DICT_VALUE = 3 AND DICT_LABEL = '取水异常核账')));
SET @sql := IF(@base_source_rows = 3 AND @base_source_exact = 3, 'SELECT 1',
               'SELECT `中止：售后来源字典 1370 的1/2/3缺失、重复或语义错位`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 1370#4 只允许「不存在」或「恰一条充值退款」；同名重复也属于污染，必须在写入前阻断。
SET @src4_rows := (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE = '1370' AND DICT_VALUE = 4);
SET @src4_exact := (SELECT COUNT(*) FROM api_dict_data
                    WHERE DICT_TYPE = '1370' AND DICT_VALUE = 4 AND DICT_LABEL = '充值退款');
SET @sql := IF(@src4_rows = 0 OR (@src4_rows = 1 AND @src4_exact = 1), 'SELECT 1',
               'SELECT `中止：字典 1370#4 重复或已被其它语义占用`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 售后功能点必须挂在订单中心的售后菜单下（包A 建的 1052 目录/菜单节点）
SET @parent := (SELECT COUNT(*) FROM api_rbac_menu WHERE ID = 1052 AND MENU_TYPE = 2 AND DATA_STATUS = 0);
SET @sql := IF(@parent = 1, 'SELECT 1', 'SELECT `中止：售后菜单节点 1052 不存在，请先执行包A 迁移`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @perm_conflict := (SELECT COUNT(*) FROM api_rbac_menu
                       WHERE ID = 1141
                         AND (MENU_TYPE <> 3 OR MENU_PARENT_ID <> 1052 OR DATA_STATUS <> 0
                           OR NOT (MENU_API_PERMS <=> 'order:aftersale:refund')
                           OR NOT (MENU_WEB_PERMS <=> 'order:aftersale:refund')));
SET @sql := IF(@perm_conflict = 0, 'SELECT 1', 'SELECT `中止：菜单ID 1141 已被其它功能点占用`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 同一权限码不得已有第二个宿主，否则先插1141再后置失败会留下局部污染。
SET @perm_host_conflict := (SELECT COUNT(*) FROM api_rbac_menu
                            WHERE ID <> 1141
                              AND (MENU_API_PERMS = 'order:aftersale:refund'
                                OR MENU_WEB_PERMS = 'order:aftersale:refund'));
SET @sql := IF(@perm_host_conflict = 0, 'SELECT 1',
               'SELECT `中止：order:aftersale:refund 已被其它菜单行占用`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 角色绑定表没有业务唯一键；重复绑定必须在 UPDATE 复活之前阻断。
SET @role_bind_count := (SELECT COUNT(*) FROM api_rbac_role_menu WHERE ROLE_ID = 1 AND MENU_ID = 1141);
SET @sql := IF(@role_bind_count <= 1, 'SELECT 1',
               'SELECT `中止：超级管理员与退款权限存在重复绑定`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 变更区 ============

-- 首次购卡异常单没有 CARD_ID；这里只放宽物理列，业务层仍按来源+动作类型 fail-closed。
ALTER TABLE `ws_after_sale_action`
  MODIFY COLUMN `CARD_ID` bigint NULL COMMENT '返还目标卡ID；首次购卡未入账退款尚未发卡时可空，其它返还必须非空';

-- 字典与权限 DML 统一放进事务；任何插入、复活或后置断言失败不得留下局部权限污染。
START TRANSACTION;

-- 一、售后来源字典追加 4=充值退款（NOT EXISTS 幂等；api_dict_data 无唯一键，IGNORE 无键可撞）
INSERT INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, '1370', 4, 4, '充值退款'
WHERE NOT EXISTS (
    SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = '1370' AND d.DICT_VALUE = 4);

-- 二、独立财务审核权限功能点（与 Controller 的 @SaCheckPermission 逐字一致，差一个字符即恒 403）
-- 列清单与包A 迁移、03-demo-baseline 的功能点行逐列一致（MENU_WEB_PERMS 与 MENU_API_PERMS 同值：
-- 前者供前端按钮鉴权，后者供 @SaCheckPermission；只写一个会让「按钮在但接口 403」或反之）。
INSERT INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,
 `MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,
 `DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`)
SELECT 1141,'发起充值退款',3,NULL,1052,4,NULL,NULL,1,
       'order:aftersale:refund','order:aftersale:refund',2,1,0,1,'20260729000000',1,'20260729000000'
WHERE NOT EXISTS (SELECT 1 FROM api_rbac_menu m WHERE m.ID = 1141);

-- 曾被逻辑删除的绑定要先复活（与包A 同一处理）：下面的 NOT EXISTS 只看「有没有这一行」，
-- 留一行 DATA_STATUS=1 的旧绑定会让插入被跳过，权限静默失效。
UPDATE `api_rbac_role_menu`
SET `DATA_STATUS` = 0, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260729000000'
WHERE `ROLE_ID` = 1 AND `MENU_ID` = 1141 AND `DATA_STATUS` <> 0;

-- 三、绑定给超管角色（角色1）。不绑等于功能点存在而无人可用，页面正常、接口恒 403
INSERT INTO `api_rbac_role_menu`
(`DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `ROLE_ID`, `MENU_ID`)
SELECT 0, 1, '20260729000000', 1, '20260729000000', 1, 1141
WHERE NOT EXISTS (
    SELECT 1 FROM api_rbac_role_menu rm WHERE rm.ROLE_ID = 1 AND rm.MENU_ID = 1141);

-- ============ 后置不变式 ============

SET @ok_src := (SELECT COUNT(*) FROM api_dict_data
                WHERE DICT_TYPE = '1370' AND DICT_VALUE = 4 AND DICT_LABEL = '充值退款');
SET @sql := IF(@ok_src = 1, 'SELECT 1', 'SELECT `中止：售后来源 1370#4 充值退款 未恰好一行`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_perm := (SELECT COUNT(*) FROM api_rbac_menu
                 WHERE MENU_TYPE = 3 AND DATA_STATUS = 0 AND MENU_API_PERMS = 'order:aftersale:refund');
SET @sql := IF(@ok_perm = 1, 'SELECT 1', 'SELECT `中止：权限码 order:aftersale:refund 未恰好一个功能点`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_bind := (SELECT COUNT(*) FROM api_rbac_role_menu
                 WHERE ROLE_ID = 1 AND MENU_ID = 1141 AND DATA_STATUS = 0);
SET @sql := IF(@ok_bind = 1, 'SELECT 1', 'SELECT `中止：order:aftersale:refund 未绑定超管角色`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

COMMIT;

SELECT 'E2E-04 包D-5 迁移完成：售后来源 1370#4 充值退款 + 权限码 order:aftersale:refund' AS RESULT;

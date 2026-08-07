-- E2E-04 售后退款与补偿 包A schema 迁移：
--   新表 ws_after_sale_action —— 售后执行统一主表（取消/申诉补偿/取水核账三条来源共用一份执行内核）；
--   字典 1370 售后来源、1371 售后动作类型、1372 售后执行状态；
--   领域事件类型字典 1363 补值 9（售后动作），配合 OpsEnum.EventType.AFTER_SALE。
--
-- 模式与既有迁移一致（抄 2026-07-23-delivery-e2e03.sql）：所有只读检查在前、任何变更在后；
-- 污染即中止且零变更；不使用存储过程。
-- 中止手法：动态 SQL SELECT `<中文原因>` 触发 ER_1054，事务外零残留。
-- 注意「IF() 跳过求值但跳不过解析」坑：凡引用可能不存在列的 SQL 一律走 PREPARE 动态串。

-- 固定连接字符集，防止调用端沿用 latin1 会话导致中文字典二次编码并被 varchar 上限截断。
SET NAMES utf8mb4;

-- ============ 只读检查区（不做任何变更） ============

-- 1) 依赖表必须存在：售后动作的外键语义落在这四张表上
SET @t_order := (SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = DATABASE() AND table_name = 'ws_order');
SET @sql := IF(@t_order = 1, 'SELECT 1', 'SELECT `中止：ws_order 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_appeal := (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'ws_delivery_appeal');
SET @sql := IF(@t_appeal = 1, 'SELECT 1', 'SELECT `中止：ws_delivery_appeal 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_card := (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ws_card');
SET @sql := IF(@t_card = 1, 'SELECT 1', 'SELECT `中止：ws_card 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @t_flow := (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'ws_wallet_flow');
SET @sql := IF(@t_flow = 1, 'SELECT 1', 'SELECT `中止：ws_wallet_flow 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) 幂等：本迁移可重复执行。若表已存在，必须是本迁移建出的形状（含四元额度列），
--    否则说明有同名异构表，停手交人工——绝不 ALTER 一张来历不明的表。
SET @t_as := (SELECT COUNT(*) FROM information_schema.tables
              WHERE table_schema = DATABASE() AND table_name = 'ws_after_sale_action');
SET @c_prod_fen := (SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'ws_after_sale_action'
                      AND column_name = 'REFUND_PRODUCT_FEN');
SET @sql := IF(@t_as = 0 OR @c_prod_fen = 1, 'SELECT 1',
               'SELECT `中止：ws_after_sale_action 已存在但缺少 REFUND_PRODUCT_FEN，疑似同名异构表`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) 字典段占位核验：1370/1371/1372 必须空闲或已是本迁移写入的值。
--    137x 段整段空闲（134x 交易段仅剩 1347-1349 不够用，135x 是配送段，混入会让分段注释失真）。
SET @d_conflict := (SELECT COUNT(*) FROM api_dict_type
                    WHERE DICT_TYPE IN ('1370', '1371', '1372')
                      AND DICT_NAME NOT IN ('售后来源', '售后动作类型', '售后执行状态'));
SET @sql := IF(@d_conflict = 0, 'SELECT 1', 'SELECT `中止：字典 1370/1371/1372 已被其他业务占用`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 4) 污染阻断：目标字典项若已有重复（同 DICT_TYPE+DICT_VALUE 出现多行），说明此前被
--    非幂等写法插过，此时继续插入只会加深污染。停手交人工去重后重跑。
SET @d_dup := (SELECT COUNT(*) FROM (
    SELECT DICT_TYPE, DICT_VALUE FROM api_dict_data
    WHERE DICT_TYPE IN ('1370', '1371', '1372')
       OR (DICT_TYPE = '1363' AND DICT_VALUE = 9)
    GROUP BY DICT_TYPE, DICT_VALUE HAVING COUNT(*) > 1
  ) d);
SET @sql := IF(@d_dup = 0, 'SELECT 1', 'SELECT `中止：目标字典项已重复，须人工去重后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 5) 权限落库前置。三条缺一不可，因为 INSERT IGNORE 的失败方式是「静默跳过」：
--    ID 被别的业务占用时权限永远进不来，而接口照常挂着 @SaCheckPermission —— 表现为
--    菜单一切正常、点执行恒 403，且数据库里查得到一条同名功能点（属于别人）。
SET @t_menu := (SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'api_rbac_menu');
SET @sql := IF(@t_menu = 1, 'SELECT 1', 'SELECT `中止：api_rbac_menu 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 父菜单：订单中心 → 申诉处理（03-demo-baseline 菜单 1052）
SET @m_parent := (SELECT COUNT(*) FROM api_rbac_menu
                  WHERE ID = 1052 AND MENU_TYPE = 2 AND DATA_STATUS = 0);
SET @sql := IF(@m_parent = 1, 'SELECT 1', 'SELECT `中止：父菜单 1052 申诉处理 不存在，售后功能点无处挂载`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 固定 ID 必须空闲或已是本迁移写入的两个功能点
SET @m_taken := (SELECT COUNT(*) FROM api_rbac_menu
                 WHERE ID IN (1139, 1140)
                   AND (MENU_API_PERMS IS NULL
                        OR MENU_API_PERMS NOT IN ('order:aftersale:query', 'order:aftersale:handle')));
SET @sql := IF(@m_taken = 0, 'SELECT 1', 'SELECT `中止：菜单 ID 1139/1140 已被其他功能点占用`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 权限码必须没有第二个宿主：同一权限码挂两行会让角色配置页出现两个开关，关掉一个也不生效
SET @m_dup := (SELECT COUNT(*) FROM api_rbac_menu
               WHERE MENU_API_PERMS IN ('order:aftersale:query', 'order:aftersale:handle')
                 AND ID NOT IN (1139, 1140));
SET @sql := IF(@m_dup = 0, 'SELECT 1', 'SELECT `中止：售后权限码已被其他菜单行占用，须人工收敛后重跑`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 变更区 ============

-- ------------------------------------------------------------------
-- 售后执行统一主表
--
-- 为什么是「一张统一执行表」而不是「案件表 + 动作表」：
--   三条来源（待接单取消 / 申诉补偿 / 取水核账）的差异只在触发时机与来源标识，
--   返还内核完全相同。拆两张表会让返还逻辑有两个入口，违反「单一出处」。
--
-- 四元额度（REFUND_PRODUCT_FEN / REFUND_SERVICE_FEN / REFUND_PRODUCT_ML）是本表的要害：
--   payWay=2 时水费与配送费**都从余额扣**，若只记一个混合总额 REFUND_AMOUNT，
--   连续多次 SERVICE_FEE_ONLY 申诉会各自按「总额」判额度，把水费额度挪去退配送费——
--   本单实际只扣过 N 分配送费，却能退出 3N。故必须按「水品 / 配送费」两条独立维度分别封顶，
--   REFUND_AMOUNT 退化为两者之和，只供 CAS 与流水使用。
-- ------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `ws_after_sale_action` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间',

  `AFTER_SALE_NO`       varchar(32)  NOT NULL COMMENT '售后号：AS + sha256(sourceType:sourceId) 截断，确定性派生（同来源重放得同号）',
  `SOURCE_TYPE`         tinyint      NOT NULL COMMENT '售后来源(1370)：1配送取消 2配送申诉 3取水异常核账',
  `SOURCE_ID`           bigint       NOT NULL COMMENT '来源主体ID：1取ws_order.ID 2取ws_delivery_appeal.ID 3取ws_order.ID。申诉必须取appealId——uk_appeal_active_task只约束待处理申诉，同任务可合法产生多条申诉',
  `ORDER_ID`            bigint       NOT NULL COMMENT '关联订单ID（额度聚合与账本核验的锚点）',
  `USER_ID`             bigint       NOT NULL COMMENT '订单归属用户ID',
  `CARD_ID`             bigint       NULL COMMENT '返还目标卡ID；首次购卡未入账退款尚未发卡时可空，其它返还必须非空',

  `ACTION_TYPE`         tinyint      NOT NULL COMMENT '动作类型(1371)：1卡内退款 2卡内补偿 3支付机构退款(包B) 4补送(包C)',
  `STRATEGY_CODE`       varchar(20)  NULL COMMENT '补偿策略码：PRODUCT_ONLY/SERVICE_FEE_ONLY/PRODUCT_AND_SERVICE/RESEND/REJECT。字符串码不入字典——api_dict_data.DICT_VALUE是tinyint、DICT_LABEL仅varchar(10)，物理上放不下；单一出处见 AfterSaleEnum.StrategyCode',
  `APPROVED_COUNT`      int          NULL COMMENT '运营批准的受影响数量（桶）；取消与取水核账为NULL',

  `REFUND_PRODUCT_FEN`  bigint       NOT NULL DEFAULT 0 COMMENT '水品权益返还金额(分)，payWay=2 专用；额度锚点=快照 waterAmountFen',
  `REFUND_SERVICE_FEN`  bigint       NOT NULL DEFAULT 0 COMMENT '配送费返还金额(分)；额度锚点=快照 deliveryFeeFen。与水品分列是为了两维独立封顶',
  `REFUND_PRODUCT_ML`   bigint       NOT NULL DEFAULT 0 COMMENT '水品权益返还水量(毫升)，payWay=3 专用；额度锚点=快照 waterMl',
  `REFUND_AMOUNT`       bigint       NOT NULL DEFAULT 0 COMMENT '返还金额合计(分)=REFUND_PRODUCT_FEN+REFUND_SERVICE_FEN，供CAS与流水使用；封顶判定不看本列',

  `CALC_SNAPSHOT`       text         NULL COMMENT '服务端计算依据快照（策略码、数量边界、额度锚点、已用额度、单价来源），出账即冻结，事后不重算',
  `ACTION_STATUS`       tinyint      NOT NULL COMMENT '执行状态(1372)：1待执行 2执行中 3已完成 4可重试 5需人工对账 6已终止',
  `VERSION`             int          NOT NULL DEFAULT 1 COMMENT '乐观锁版本，状态机CAS的前态条件之一',
  `RETRY_COUNT`         int          NOT NULL DEFAULT 0 COMMENT '重试次数，达上限转需人工对账',
  `NEXT_RETRY_TIME`     varchar(14)  NULL COMMENT '下次可重试时间，Worker 预筛条件',

  `REFUND_ID`           bigint       NULL COMMENT '支付机构退款单ID（包B），卡内返还恒为NULL',
  `RESULT_ORDER_ID`     bigint       NULL COMMENT '补送产生的子订单ID（包C）',
  `RESULT_TASK_ID`      bigint       NULL COMMENT '补送产生的任务ID（包C）',

  `APPROVE_BY`          bigint       NULL COMMENT '批准人（PC员工ID）；用户自助取消为NULL',
  `APPROVE_TIME`        varchar(14)  NULL COMMENT '批准时间',
  `FINISH_TIME`         varchar(14)  NULL COMMENT '终态时间（完成/终止/转人工）',
  `LAST_ERROR`          varchar(500) NULL COMMENT '最近一次失败原因，转人工对账时的排查依据',

  PRIMARY KEY (`ID`),
  -- 确定性售后号：同来源重放得同号，撞键即幂等命中，不靠应用层查重
  UNIQUE INDEX `uk_after_sale_no` (`AFTER_SALE_NO`),
  -- R0-7 同一来源只允许一个售后执行单。注意本键不带 DATA_STATUS：
  -- 逻辑删除的行仍占键，防止「删了再建」绕过唯一约束重复返还
  UNIQUE INDEX `uk_after_sale_source` (`SOURCE_TYPE`, `SOURCE_ID`),
  -- 复合而非单列：累计封顶的聚合谓词是 ORDER_ID + ACTION_STATUS=已完成，且它是 FOR UPDATE 锁定读。
  -- 只建 ORDER_ID 时，SUCCESS 行随业务增长后优化器可能改选 idx_after_sale_status，
  -- 那条锁定读就会对全表「已完成」的行加 X 锁，把所有订单的售后执行互相串行化。
  INDEX `idx_after_sale_order` (`ORDER_ID`, `ACTION_STATUS`),
  INDEX `idx_after_sale_status` (`ACTION_STATUS`, `NEXT_RETRY_TIME`),
  INDEX `idx_after_sale_card` (`CARD_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='售后执行动作表（E2E-04 包A）';

-- ------------------------------------------------------------------
-- 字典
-- ------------------------------------------------------------------
-- 字典幂等只能靠 NOT EXISTS，不能靠 INSERT IGNORE：
-- api_dict_type / api_dict_data 上除主键外没有任何唯一键（自增 ID 每次都不同），
-- IGNORE 无键可撞，重复执行会静默插入整套重复项——已实测重跑一次即翻倍。
-- 既有迁移（2026-07-23-delivery-e2e03.sql:490-493）用的正是 NOT EXISTS，此处沿用同一范式。
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (
  SELECT '售后来源' AS DICT_NAME, '1370' AS DICT_TYPE, '售后执行动作的触发来源' AS DICT_REMARK
  UNION ALL SELECT '售后动作类型', '1371', '售后执行动作的资金/履约形态'
  UNION ALL SELECT '售后执行状态', '1372', '售后执行动作状态机'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
  SELECT '1370' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '配送取消' AS DICT_LABEL
  UNION ALL SELECT '1370', 2, 2, '配送申诉'
  UNION ALL SELECT '1370', 3, 3, '取水异常核账'
  UNION ALL SELECT '1371', 1, 1, '卡内退款'
  UNION ALL SELECT '1371', 2, 2, '卡内补偿'
  UNION ALL SELECT '1371', 3, 3, '机构退款'
  UNION ALL SELECT '1371', 4, 4, '补送'
  UNION ALL SELECT '1372', 1, 1, '待执行'
  UNION ALL SELECT '1372', 2, 2, '执行中'
  UNION ALL SELECT '1372', 3, 3, '已完成'
  UNION ALL SELECT '1372', 4, 4, '可重试'
  UNION ALL SELECT '1372', 5, 5, '需人工对账'
  UNION ALL SELECT '1372', 6, 6, '已终止'
  -- 领域事件类型补「售后动作」：三条来源的 eventKey 不同（orderNo/appealId/orderNo），
  -- 复用 DELIVERY_NODE 会让取水核账事件挂进「配送节点」、按类型检索不出来
  UNION ALL SELECT '1363', 9, 9, '售后动作'
) s
WHERE NOT EXISTS (
  SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE
);

-- ------------------------------------------------------------------
-- PC 售后处理权限（AdminAfterSaleController 的两个权限码）
--
-- 为什么写在迁移而不是只改 03-demo-baseline.sql：baseline 只在空数据卷首次初始化时执行，
-- 已有环境不会重跑；而且 baseline 里「超级管理员绑定全部启用菜单」那段跑在本迁移之前，
-- 即便新菜单进了 baseline，本迁移之后新增的行也拿不到角色绑定。故菜单与绑定必须在这里一起补。
--
-- 固定 ID 而不是自增：功能点会被 api_rbac_role_menu 按 MENU_ID 引用，ID 随环境漂移
-- 会让不同环境的角色授权对不上号，导出/比对权限时无从判断哪一行对应哪个权限码。
--
-- 挂在 1052 申诉处理 之下：包A 还没有独立的售后台账页面，运营触发售后执行与取水核账的入口
-- 就在申诉处理与订单详情两处。前端售后页落地后只需改 MENU_PARENT_ID，权限码与 ID 都不变。
-- ------------------------------------------------------------------
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`) VALUES
(1139,'售后台账查询',3,NULL,1052,2,NULL,NULL,1,'order:aftersale:query','order:aftersale:query',2,1,0,1,'20260729120000',1,'20260729120000'),
(1140,'售后执行与核账',3,NULL,1052,3,NULL,NULL,1,'order:aftersale:handle','order:aftersale:handle',2,1,0,1,'20260729120000',1,'20260729120000');

-- 曾被逻辑删除的绑定要先复活：下面的 NOT EXISTS 只看「有没有这一行」，
-- 留一行 DATA_STATUS=1 的旧绑定会让插入被跳过，权限静默失效。范围限定超管与这两个功能点。
UPDATE `api_rbac_role_menu`
SET `DATA_STATUS` = 0, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260729120000'
WHERE `ROLE_ID` = 1 AND `MENU_ID` IN (1139, 1140) AND `DATA_STATUS` <> 0;

INSERT IGNORE INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260729120000',1,'20260729120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` IN (1139, 1140)
  AND m.`DATA_STATUS` = 0
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

-- ============ 后置不变式（不满足即中止，但表已建——本迁移无破坏性变更，可安全重跑） ============

SET @ok_table := (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'ws_after_sale_action');
SET @sql := IF(@ok_table = 1, 'SELECT 1', 'SELECT `中止：ws_after_sale_action 建表未生效`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 两条唯一键是 R0-7 与幂等的物理保证，缺一即整个包A 的防重复返还失效
SET @ok_uk := (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ws_after_sale_action'
                 AND INDEX_NAME IN ('uk_after_sale_no', 'uk_after_sale_source') AND NON_UNIQUE = 0);
SET @sql := IF(@ok_uk = 2, 'SELECT 1', 'SELECT `中止：uk_after_sale_no / uk_after_sale_source 未全部建立`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 四元额度列缺任一列，封顶就退化成混合总额判定，payWay=2 的配送费可超额返还
SET @ok_quota := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE() AND table_name = 'ws_after_sale_action'
                    AND column_name IN ('REFUND_PRODUCT_FEN', 'REFUND_SERVICE_FEN', 'REFUND_PRODUCT_ML', 'REFUND_AMOUNT'));
SET @sql := IF(@ok_quota = 4, 'SELECT 1', 'SELECT `中止：四元额度列不齐`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 包A只能核验自己写入的13项，不能依赖后续 d3 才会追加的1370#4。
-- 旧库按文件名顺序执行时 a 必然早于 d3；若在这里要求14项，真实升级会在包A中止，
-- 而「最新 init 已预置1370#4」的验收库会把该缺陷遮住。
SET @ok_dict_owned := (SELECT COUNT(*) FROM api_dict_data
                       WHERE (DICT_TYPE = '1370' AND ((DICT_VALUE = 1 AND DICT_LABEL = '配送取消')
                                                   OR (DICT_VALUE = 2 AND DICT_LABEL = '配送申诉')
                                                   OR (DICT_VALUE = 3 AND DICT_LABEL = '取水异常核账')))
                          OR (DICT_TYPE = '1371' AND ((DICT_VALUE = 1 AND DICT_LABEL = '卡内退款')
                                                   OR (DICT_VALUE = 2 AND DICT_LABEL = '卡内补偿')
                                                   OR (DICT_VALUE = 3 AND DICT_LABEL = '机构退款')
                                                   OR (DICT_VALUE = 4 AND DICT_LABEL = '补送')))
                          OR (DICT_TYPE = '1372' AND ((DICT_VALUE = 1 AND DICT_LABEL = '待执行')
                                                   OR (DICT_VALUE = 2 AND DICT_LABEL = '执行中')
                                                   OR (DICT_VALUE = 3 AND DICT_LABEL = '已完成')
                                                   OR (DICT_VALUE = 4 AND DICT_LABEL = '可重试')
                                                   OR (DICT_VALUE = 5 AND DICT_LABEL = '需人工对账')
                                                   OR (DICT_VALUE = 6 AND DICT_LABEL = '已终止'))));
SET @sql := IF(@ok_dict_owned = 13, 'SELECT 1', 'SELECT `中止：包A自有售后字典13项不完整或存在重复`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 1363#9 与 OpsEnum.EventType.AFTER_SALE 配对：缺它则售后审计事件按类型检索不出来
-- （getType 对未登记值 fail-closed），必须与上面三段字典同等对待
SET @ok_evt := (SELECT COUNT(*) FROM api_dict_data WHERE DICT_TYPE = '1363' AND DICT_VALUE = 9);
SET @sql := IF(@ok_evt = 1, 'SELECT 1', 'SELECT `中止：领域事件类型 1363#9 售后动作 未恰好一行`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 权限码是接口能不能被调用的物理开关：功能点缺失或没绑给超管，PC 上的售后执行与核账确认
-- 会以 403 收场，而页面、菜单、字典全都正常，排查方向极易跑偏。故与表、字典同等对待。
SET @ok_perm := (SELECT COUNT(*) FROM api_rbac_menu
                 WHERE MENU_TYPE = 3 AND DATA_STATUS = 0 AND MENU_PARENT_ID = 1052
                   AND MENU_API_PERMS IN ('order:aftersale:query', 'order:aftersale:handle'));
SET @sql := IF(@ok_perm = 2, 'SELECT 1', 'SELECT `中止：售后权限功能点未恰好落库两条`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @ok_bind := (SELECT COUNT(*) FROM api_rbac_role_menu rm
                 JOIN api_rbac_menu m ON m.`ID` = rm.`MENU_ID`
                 WHERE rm.`ROLE_ID` = 1 AND rm.`DATA_STATUS` = 0 AND m.`DATA_STATUS` = 0
                   AND m.`MENU_API_PERMS` IN ('order:aftersale:query', 'order:aftersale:handle'));
SET @sql := IF(@ok_bind = 2, 'SELECT 1', 'SELECT `中止：售后权限未绑定超级管理员角色`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SELECT 'E2E-04 包A 迁移完成：ws_after_sale_action + 字典 1370/1371/1372 + 1363#9 + 权限 order:aftersale:query/handle' AS RESULT;

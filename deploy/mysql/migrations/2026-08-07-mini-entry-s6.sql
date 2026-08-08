-- ============================================================
-- 小程序入口与运营配置底座（S6，2026-08-07）
--
-- 新表 ws_mini_entry_config（同键恒一行、状态就地流转、VERSION CAS）+
-- 字典 1384/1385/1386 + 既有首页四入口的已发布基线（行为与硬编码一致）。
-- 小程序只读已发布；外链域名白名单默认空=全拒绝；Tabbar 与能力权限不受配置覆盖。
-- 幂等：CREATE TABLE IF NOT EXISTS + 字典 NOT EXISTS + 种子 INSERT IGNORE。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ws_mini_entry_config` (
  `ID`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`   tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`     bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`   varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`     bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`   varchar(14)  NOT NULL COMMENT '更新时间',
  `ENTRY_KEY`     varchar(50)  NOT NULL COMMENT '入口键(max50)：功能入口=路由编号(U07/U08/U10/U13/U16等)；内容位=protocol/faq/service/notice',
  `ENTRY_TYPE`    tinyint      NOT NULL COMMENT '入口类型(1384)：1功能入口 2内容链接 3维护公告',
  `ENTRY_NAME`    varchar(50)  NOT NULL COMMENT '展示名称(max50)',
  `SORT_NO`       int          NOT NULL DEFAULT 0 COMMENT '排序（小程序按此升序稳定排列）',
  `ENABLED_FLAG`  tinyint      NOT NULL DEFAULT 2 COMMENT '是否启用(1)：1否 2是；停用或未发布的入口小程序默认隐藏',
  `JUMP_TYPE`     tinyint      COMMENT '跳转类型(1385)：1内部路由 2外部链接；维护公告无跳转可空',
  `ROUTE_ID`      varchar(10)  COMMENT '内部路由编号（服务端白名单+前端路由合同双重校验）',
  `EXTERNAL_URL`  varchar(500) COMMENT '外部链接(max500)：仅 https 且域名在白名单，禁 javascript:/任意 scheme',
  `CONTENT_TEXT`  varchar(500) COMMENT '内容文本(max500)：维护公告正文',
  `CONFIG_STATUS` tinyint      NOT NULL COMMENT '配置状态(1386)：1草稿 2已发布 3已撤回；小程序只读已发布',
  `PUBLISH_TIME`  varchar(14)  COMMENT '最近发布时间',
  `VERSION`       int          NOT NULL DEFAULT 1 COMMENT '乐观锁：状态流转与内容修改的 CAS 前态（并发发布只有一个有效版本）',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mini_entry_key` (`ENTRY_KEY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='小程序入口与运营配置表（S6 配置底座）';

INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (SELECT '小程序入口类型' AS DICT_NAME, '1384' AS DICT_TYPE, '功能入口/内容链接/维护公告' AS DICT_REMARK) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1384' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '功能入口' AS DICT_LABEL
  UNION ALL SELECT '1384', 2, 2, '内容链接'
  UNION ALL SELECT '1384', 3, 3, '维护公告'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (SELECT '小程序入口跳转类型' AS DICT_NAME, '1385' AS DICT_TYPE, '内部路由/外部链接' AS DICT_REMARK) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1385' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '内部路由' AS DICT_LABEL
  UNION ALL SELECT '1385', 2, 2, '外部链接'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (SELECT '小程序入口配置状态' AS DICT_NAME, '1386' AS DICT_TYPE, '草稿/已发布/已撤回' AS DICT_REMARK) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1386' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '草稿' AS DICT_LABEL
  UNION ALL SELECT '1386', 2, 2, '已发布'
  UNION ALL SELECT '1386', 3, 3, '已撤回'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

INSERT IGNORE INTO `ws_mini_entry_config`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`ENTRY_KEY`,`ENTRY_TYPE`,`ENTRY_NAME`,`SORT_NO`,`ENABLED_FLAG`,`JUMP_TYPE`,`ROUTE_ID`,`EXTERNAL_URL`,`CONTENT_TEXT`,`CONFIG_STATUS`,`PUBLISH_TIME`,`VERSION`)
VALUES
(1,0,1,'20260807120000',1,'20260807120000','U07',1,'附近水站',10,2,1,'U07',NULL,NULL,2,'20260807120000',1),
(2,0,1,'20260807120000',1,'20260807120000','U08',1,'配送订水',20,2,1,'U08',NULL,NULL,2,'20260807120000',1),
(3,0,1,'20260807120000',1,'20260807120000','U10',1,'充值',30,2,1,'U10',NULL,NULL,2,'20260807120000',1),
(4,0,1,'20260807120000',1,'20260807120000','U13',1,'家庭资料',40,2,1,'U13',NULL,NULL,2,'20260807120000',1),
(5,0,1,'20260807120000',1,'20260807120000','notice',3,'维护公告',0,1,NULL,NULL,NULL,'系统维护期间部分功能暂不可用',1,NULL,1);

-- PC 管理菜单（系统管理组）+ 超管绑定
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`)
VALUES
(1035,'小程序入口配置',2,NULL,607,7,'minientry','/system/minientry',1,NULL,NULL,1,1,0,1,'20260807120000',1,'20260807120000'),
(1150,'入口配置变更',3,NULL,1035,1,NULL,NULL,1,'system:minientry:edit','system:minientry:edit',2,1,0,1,'20260807120000',1,'20260807120000');

INSERT IGNORE INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260807120000',1,'20260807120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` IN (1035, 1150)
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

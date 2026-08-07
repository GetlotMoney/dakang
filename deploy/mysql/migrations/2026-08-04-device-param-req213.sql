-- ============================================================
-- 设备参数模型（REQ-213-S1，2026-08-04）：两张纯增量新表，不触碰任何既有表。
--
-- 【为什么必须有这份迁移】
-- init/02-ws-business.sql 只在**空库首次启动**时由 docker-entrypoint-initdb.d 执行，
-- 既有主库永远不会再跑它。而本轮代码在指令下发路径（WsCommandServiceImpl.send 与
-- DeviceControlServiceImpl.preview）里、**建指令之前**就要查 ws_device_param_def
-- 取参数定义。表不存在 ⇒ 1146 Table doesn't exist ⇒ 参数同步与价格同步整体打不出去。
-- 这与 SchemaParityTest 类注释记录的那次 P0（ws_after_sale_action 只建在 migrations
-- 忘了同步 init，导致已验收的订单中心整体 1146）是同一形状的镜像版本：
-- 单测自建表，全量测试不会红，只有部署到既有环境才炸。
--
-- 与 init/02-ws-business.sql 的表定义逐字一致（双轨）。幂等：表已存在则跳过。
-- 执行方式与既有迁移一致，由部署方显式执行，不进 compose 自动流程。
-- ============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 参数定义注册表
-- ----------------------------
SET @has := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
  AND table_name = 'ws_device_param_def');
SET @sql := IF(@has > 0, 'SELECT 1',
  'CREATE TABLE `ws_device_param_def` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT ''主键'',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT ''逻辑删除：0正常 1删除'',
  `CREATE_BY`        bigint       NOT NULL COMMENT ''创建人ID'',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT ''创建时间'',
  `UPDATE_BY`        bigint       NOT NULL COMMENT ''更新人ID'',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT ''更新时间'',
  `CMD_TYPE`         tinyint      NOT NULL COMMENT ''所属指令类型(1320)：6参数同步 8价格同步'',
  `PARAM_KEY`        varchar(32)  COLLATE utf8mb4_bin NOT NULL COMMENT ''参数键名，须匹配 ^[A-Za-z][A-Za-z0-9_]{0,31}$；列级 utf8mb4_bin：表默认 ci 会让唯一键把 mode/MODE 折叠成同一行，而 Java 侧 registry 查找大小写敏感，两者不同构即产生「改个大小写就绕过已登记键校验、回写却落到原行」'',
  `PARAM_NAME`       varchar(50)  NOT NULL COMMENT ''中文名（运营可读）'',
  `VALUE_TYPE`       tinyint      NOT NULL COMMENT ''值类型：1整数 2小数 3字符串 4布尔'',
  `VALUE_UNIT`       varchar(16)  NULL COMMENT ''单位（毫升/秒/摄氏度/分…），无单位留空'',
  `VALUE_MIN`        varchar(32)  NULL COMMENT ''数值下限（含），非数值类型留空'',
  `VALUE_MAX`        varchar(32)  NULL COMMENT ''数值上限（含），非数值类型留空'',
  `VALUE_ENUM`       varchar(255) NULL COMMENT ''允许值枚举，逗号分隔；留空表示不限枚举'',
  `DEF_STATUS`       tinyint      NOT NULL COMMENT ''状态：1启用（施加校验） 2停用（视同未登记）'',
  `DEF_REMARK`       varchar(255) NULL COMMENT ''备注：来源依据（厂家文档/协议版本），不得凭空登记'',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_param_def_key` (`CMD_TYPE`, `PARAM_KEY`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT=''设备参数定义注册表（REQ-213）''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------
-- 2. 设备参数快照表
-- ----------------------------
SET @has := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
  AND table_name = 'ws_device_param');
SET @sql := IF(@has > 0, 'SELECT 1',
  'CREATE TABLE `ws_device_param` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT ''主键'',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT ''逻辑删除：0正常 1删除'',
  `CREATE_BY`        bigint       NOT NULL COMMENT ''创建人ID'',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT ''创建时间'',
  `UPDATE_BY`        bigint       NOT NULL COMMENT ''更新人ID'',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT ''更新时间'',
  `DEVICE_ID`        bigint       NOT NULL COMMENT ''设备ID'',
  `PARAM_KEY`        varchar(32)  COLLATE utf8mb4_bin NOT NULL COMMENT ''参数键名（列级 utf8mb4_bin，与 Java 大小写敏感口径一致）'',
  `PARAM_VALUE`      varchar(64)  NOT NULL COMMENT ''平台侧认为设备当前生效的值（标量原文）'',
  `PARAM_VERSION`    int          NOT NULL DEFAULT 1 COMMENT ''该键的同步版本，每次成功回写 +1'',
  `SOURCE_CMD_NO`    varchar(40)  NOT NULL COMMENT ''写入本值的指令号（溯源用）'',
  `SOURCE_CMD_ID`    bigint       NOT NULL COMMENT ''写入本值的指令ID（ws_command.ID）。单调守卫：自增即平台下发顺序，只有更大的 ID 才能覆盖——设备断网补传或消息重投会让更早指令的 result 后到，按到达顺序写会把新值覆盖成旧值且版本号还 +1。绝不用设备时钟排序'',
  `SYNC_TIME`        varchar(14)  NOT NULL COMMENT ''设备回报成功的时间（result 的 finishTs）'',
  `REGISTERED_FLAG`  tinyint      NOT NULL DEFAULT 0 COMMENT ''写入时该键是否已在定义表登记：0未登记 1已登记'',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_device_param` (`DEVICE_ID`, `PARAM_KEY`),
  INDEX `idx_device_param_cmd` (`SOURCE_CMD_NO`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT=''设备参数快照表（REQ-213）''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------
-- 3. 参数定义初始灌值：只灌有文档来源的键，绝不发明设备参数。
--    priceVersion 是当前唯一有据可查的（docs/mqtt-topics.md 价格同步报文约定 +
--    设备模拟器 result 回带）；PC 批量对话框示例值为字符串 "PV-20260730-01"，
--    故按实际形态登记为 3字符串，不按主观期望登记为整数（那会打断已验收路径）。
--    参数同步(cmdType=6)的业务键在厂家答复 V-12.3 前保持零登记。
-- ----------------------------
INSERT IGNORE INTO `ws_device_param_def`
  (`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,
   `CMD_TYPE`,`PARAM_KEY`,`PARAM_NAME`,`VALUE_TYPE`,`VALUE_UNIT`,`VALUE_MIN`,`VALUE_MAX`,`VALUE_ENUM`,
   `DEF_STATUS`,`DEF_REMARK`)
VALUES
  (0,1,'20260804000000',1,'20260804000000',
   8,'priceVersion','价格版本号',3,NULL,NULL,NULL,NULL,
   1,'来源：docs/mqtt-topics.md 价格同步报文约定 + 设备模拟器 result 回带；厂商映射待 V-12 确认');

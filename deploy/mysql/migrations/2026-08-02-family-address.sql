-- ============================================================
-- 家庭资料与配送地址落地（2026-08-02）：两张纯增量新表，不触碰任何既有表。
-- 此前两能力仅前端本机存储；本迁移补齐服务端，配送下单可按 addressId 服务端解引用取号。
-- 与 init/02-ws-business.sql 的表定义逐字一致（双轨）。幂等：表已存在则跳过。
-- ============================================================

SET NAMES utf8mb4;

SET @has := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
  AND table_name = 'ws_user_address');
SET @sql := IF(@has > 0, 'SELECT 1',
  'CREATE TABLE `ws_user_address` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT ''主键'',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT ''逻辑删除：0正常 1删除'',
  `CREATE_BY`           bigint       NOT NULL COMMENT ''创建人ID'',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT ''创建时间'',
  `UPDATE_BY`           bigint       NOT NULL COMMENT ''更新人ID'',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT ''更新时间'',
  `USER_ID`             bigint       NOT NULL COMMENT ''归属用户（ws_user.ID）；读写恒以会话人过滤'',
  `CONTACT_NAME`        varchar(30)  NOT NULL COMMENT ''联系人姓名(max30)'',
  `CONTACT_PHONE`       varchar(11)  NOT NULL COMMENT ''联系电话；展示必须经 PhoneMask 脱敏，不回流前端'',
  `REGION`              varchar(100) NOT NULL COMMENT ''省市区(max100)'',
  `ADDRESS_DETAIL`      varchar(200) NOT NULL COMMENT ''详细地址(max200)'',
  `IS_DEFAULT`          tinyint      NOT NULL DEFAULT 0 COMMENT ''默认地址：1是 0否；同用户至多一条为1（服务层互斥）'',
  `LOCATION_AUTHORIZED` tinyint      NOT NULL DEFAULT 0 COMMENT ''是否已授权定位取点：1是 0否'',
  PRIMARY KEY (`ID`),
  INDEX `idx_uaddr_user` (`USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT=''用户配送地址簿''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @has := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
  AND table_name = 'ws_family_profile');
SET @sql := IF(@has > 0, 'SELECT 1',
  'CREATE TABLE `ws_family_profile` (
  `ID`                   bigint       NOT NULL AUTO_INCREMENT COMMENT ''主键'',
  `DATA_STATUS`          tinyint      NOT NULL DEFAULT 0 COMMENT ''逻辑删除：0正常 1删除'',
  `CREATE_BY`            bigint       NOT NULL COMMENT ''创建人ID'',
  `CREATE_TIME`          varchar(14)  NOT NULL COMMENT ''创建时间'',
  `UPDATE_BY`            bigint       NOT NULL COMMENT ''更新人ID'',
  `UPDATE_TIME`          varchar(14)  NOT NULL COMMENT ''更新时间'',
  `USER_ID`              bigint       NOT NULL COMMENT ''归属用户（ws_user.ID）；一人一份'',
  `PRIVACY_CONSENT_TIME` varchar(14)  NOT NULL COMMENT ''隐私说明同意时间（首次保存记服务端时间，后续更新不改写）'',
  `MEMBER_COUNT`         int          NULL COMMENT ''家庭人数（自愿）'',
  `WATER_HABIT_NOTE`     varchar(200) NULL COMMENT ''用水习惯备注(max200，自愿)'',
  PRIMARY KEY (`ID`),
  UNIQUE INDEX `uk_family_user` (`USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT=''家庭资料（自愿信息）''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 后置不变式：两表存在
SELECT
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
    AND table_name = 'ws_user_address') AS addr_table,
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
    AND table_name = 'ws_family_profile') AS family_table;

SELECT '2026-08-02 迁移完成：家庭资料与配送地址两表就绪' AS RESULT;

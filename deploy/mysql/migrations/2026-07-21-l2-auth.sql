-- ============================================================
-- L2-AUTH 数据迁移（ws_user 身份唯一约束 + openid 大小写敏感 + gender 可空）
-- 幂等；冲突数据 SIGNAL 失败（不自动合并/删除账号）；仅在独立临时 MySQL 验证，禁止执行主数据库。
-- 执行前备份：mysqldump -uroot -p dakang > backup-$(date +%F).sql
-- 用法：docker exec -i <容器> mysql --default-character-set=utf8mb4 -uroot -p'<密码>' <库> < 本文件
--
-- 约束（身份唯一性与冲突保护规则）：
--   ① USER_PHONE 唯一 uk_user_phone；② WECHAT_XCX_OPENID 唯一 uk_user_wechat_xcx_openid
--      （大小写精确、不含 DATA_STATUS、满足微信字段长度）；③ openid 列改 utf8mb4_bin；④ USER_GENDER 可空；
--   ⑤ 空串 openid 规范为 NULL；⑥ 迁移前检查重复手机号/重复非空 openid/异常手机号 + 既有同名索引结构；
--   ⑦ 身份冲突或同名索引结构不符让迁移失败——**先全部只读校验后再改数据/结构**，
--      任一校验失败时不执行任何 UPDATE/ALTER，保证污染场景下结构与数据零变化。
-- ============================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS l2_auth_migrate;

DELIMITER //
CREATE PROCEDURE l2_auth_migrate()
BEGIN
  DECLARE cnt INT DEFAULT 0;
  DECLARE cnt2 INT DEFAULT 0;

  -- ================= 阶段一：只读校验（任一失败即中止，未做任何改动） =================

  -- 6a 重复手机号（含逻辑删除账号，唯一键不含 DATA_STATUS）。
  SELECT COUNT(*) INTO cnt FROM (
    SELECT USER_PHONE FROM ws_user GROUP BY USER_PHONE HAVING COUNT(*) > 1
  ) t;
  IF cnt > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'L2-AUTH 迁移中止：存在重复手机号，请人工核查，不自动合并/删除';
  END IF;

  -- 6b 重复非空 openid（大小写敏感：BINARY 分组；排除 '' 与 NULL——空串稍后规范为 NULL、多 NULL 允许）。
  SELECT COUNT(*) INTO cnt FROM (
    SELECT BINARY WECHAT_XCX_OPENID AS o FROM ws_user
     WHERE WECHAT_XCX_OPENID IS NOT NULL AND WECHAT_XCX_OPENID <> ''
     GROUP BY BINARY WECHAT_XCX_OPENID HAVING COUNT(*) > 1
  ) t;
  IF cnt > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'L2-AUTH 迁移中止：存在重复 openid（大小写敏感），请人工核查';
  END IF;

  -- 6c 异常手机号（非 1 开头 11 位）。
  SELECT COUNT(*) INTO cnt FROM ws_user WHERE USER_PHONE NOT REGEXP '^1[0-9]{10}$';
  IF cnt > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'L2-AUTH 迁移中止：存在异常手机号，请人工核查';
  END IF;

  -- 6d 既有同名索引结构校验：若已存在同名索引，必须唯一 + 恰好单列 + 列名正确（否则中止，不静默接受错误索引）。
  --    「恰好单列」同时保证索引不含 DATA_STATUS 或任何附加列。
  SELECT COUNT(*) INTO cnt FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'ws_user' AND index_name = 'uk_user_phone';
  IF cnt > 0 THEN
    SELECT COUNT(*) INTO cnt2 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'ws_user' AND index_name = 'uk_user_phone'
       AND NON_UNIQUE = 0 AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'USER_PHONE';
    IF cnt <> 1 OR cnt2 <> 1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'L2-AUTH 迁移中止：已存在同名 uk_user_phone 但结构不符（须唯一且仅含 USER_PHONE）';
    END IF;
  END IF;

  SELECT COUNT(*) INTO cnt FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'ws_user' AND index_name = 'uk_user_wechat_xcx_openid';
  IF cnt > 0 THEN
    SELECT COUNT(*) INTO cnt2 FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'ws_user' AND index_name = 'uk_user_wechat_xcx_openid'
       AND NON_UNIQUE = 0 AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'WECHAT_XCX_OPENID';
    IF cnt <> 1 OR cnt2 <> 1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'L2-AUTH 迁移中止：已存在同名 uk_user_wechat_xcx_openid 但结构不符（须唯一且仅含 WECHAT_XCX_OPENID）';
    END IF;
  END IF;

  -- ================= 阶段二：数据规范化 + 结构变更（仅在阶段一全通过后执行） =================

  -- ⑤ 空串 openid 规范为 NULL（幂等）。
  UPDATE ws_user SET WECHAT_XCX_OPENID = NULL WHERE WECHAT_XCX_OPENID = '';

  -- ④ USER_GENDER 可空（幂等，重复执行无害）。
  ALTER TABLE ws_user MODIFY COLUMN USER_GENDER tinyint NULL COMMENT '用户性别10000（L2-AUTH：微信登录建户可空）';

  -- ③ openid 列改大小写敏感（utf8mb4_bin），长度满足微信 openid（幂等）。
  ALTER TABLE ws_user
    MODIFY COLUMN WECHAT_XCX_OPENID varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL
    COMMENT '用户小程序openid（L2-AUTH：大小写敏感精确比较）';

  -- ①② 唯一约束（不含 DATA_STATUS；已存在且经上方校验合格则跳过，保证幂等）。
  SELECT COUNT(*) INTO cnt FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'ws_user' AND index_name = 'uk_user_phone';
  IF cnt = 0 THEN
    ALTER TABLE ws_user ADD UNIQUE KEY uk_user_phone (USER_PHONE);
  END IF;

  SELECT COUNT(*) INTO cnt FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 'ws_user' AND index_name = 'uk_user_wechat_xcx_openid';
  IF cnt = 0 THEN
    ALTER TABLE ws_user ADD UNIQUE KEY uk_user_wechat_xcx_openid (WECHAT_XCX_OPENID);
  END IF;
END//
DELIMITER ;

CALL l2_auth_migrate();
DROP PROCEDURE l2_auth_migrate;

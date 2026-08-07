-- ============================================================
-- 员工口令改单向哈希（R-201，2026-08-05）
--
-- 背景：员工口令此前以 RSA 密文存储，持私钥（仓库根 .env）即可解出全部明文；
-- 初始口令固定为手机号后 6 位，而员工列表对所有登录员工可见手机号，
-- 等于未改密账号可被任意同事推算接管。
--
-- 本迁移与新 JAR 配套（两者必须同轮部署，先库后包）：
--   1) api_employee 增列 PWD_CHANGE_FLAG（是否需强制修改密码1：1否 2是，默认 1）；
--   2) 存量 RSA 密文口令一次性重置为演示口令的 BCrypt 哈希（fail-closed：
--      新代码对非 BCrypt 存储一律判"账号或密码错误"，不保留旧格式兼容分支）。
--      当前主库仅 admin 一行；其余环境由 init/acc-seed 直接种 BCrypt。
--
-- 与 init/01-base.sql 的列定义逐字一致（双轨，SchemaParityTest 看守）。
-- 幂等：重复执行结果相同。回滚：本迁移不删数据；如需回退仅能随旧 JAR 一起
-- 恢复备份（哈希不可逆，无法还原成 RSA 密文）。
--
-- 【执行方式·强制】必须以文件重定向执行：
--     mysql -h127.0.0.1 -P3308 -uroot -p"$DAKANG_DB_PASSWORD" dakang < 本文件
--   禁止复制进未加引号的 heredoc、双引号字符串或 mysql -e "..."。
--   本文件含 `$` 字面量（BCrypt 哈希 `$2a$10$...`），会被 shell 变量展开吃掉：
--   展开后 UPDATE 写进去的是一段残缺字符串，而若守卫也写成 `LIKE '$2%'` 则一并退化
--   （`LIKE '%'` 恒真、`NOT LIKE '%'` 恒假），于是全部哨兵"通过"、打印"迁移完成"，
--   实则 admin 口令已损坏，换包后 PC 全员登录锁死且无任何红灯。
--   故本文件所有守卫条件一律<b>不含 `$` 字面量</b>：用 `CHAR(36 USING utf8mb4)` 表示 `$`。
--   这样即便文件被 shell 污染，守卫本身仍然完整，会照常命中并中止。
--
-- 【停机窗口】执行本迁移即进入 PC 登录停机窗口，直到新 JAR 部署完成：
--   旧 JAR 对 BCrypt 串走 RSAUtils.decrypt 会抛异常，admin 一律"账号或密码错误"；
--   新 JAR 对旧库（缺 PWD_CHANGE_FLAG 列）则全字段 SELECT 报 1054。故必须先库后包、连续执行。
--   中止路径同样处于该窗口内（无事务回滚），人工处置期间 PC 不可登录。
-- ============================================================

SET NAMES utf8mb4;

-- 前置一：核心表必须存在
SET @ok := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
  AND table_name = 'api_employee');
SET @sql := IF(@ok = 1, 'SELECT 1', 'SELECT `中止：api_employee 缺失`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 前置二：除 admin 外不得存在其他遗留口令行。
-- 本迁移只重置 admin（其余账号无从得知新口令，重置等于静默锁死）。若真实库另有员工，
-- 必须在执行前人工决定处置方式。该检查前置到任何写操作之前——放在末尾会留下
-- 「admin 已转 BCrypt、脚本报错退出」的半执行状态（ALTER/UPDATE 已隐式提交，无法回滚）。
-- BCrypt 判据（不含 `$` 字面量，`$` 一律写作 CHAR(36 USING utf8mb4)）：
--   总长 60、第 1/4/7 位为 `$`、第 2 位为 `2`，即 `$2x$NN$` + 53 位。
-- 只验长度不足以判定：任意 60 字符串都能蒙混过关，而 RSA 密文实测 172 字符——
-- 一旦有第三种存储格式恰为 60 位，长度判据会把它当成已迁移而静默放行。
SET @legacy_other := (SELECT COUNT(*) FROM `api_employee`
  WHERE `LOGIN_NAME` <> 'admin'
    AND NOT (CHAR_LENGTH(`LOGIN_PWD`) = 60
        AND SUBSTRING(`LOGIN_PWD`, 1, 1) = CHAR(36 USING utf8mb4)
        AND SUBSTRING(`LOGIN_PWD`, 2, 1) = '2'
        AND SUBSTRING(`LOGIN_PWD`, 4, 1) = CHAR(36 USING utf8mb4)
        AND SUBSTRING(`LOGIN_PWD`, 7, 1) = CHAR(36 USING utf8mb4)));
SET @sql := IF(@legacy_other = 0, 'SELECT 1',
  'SELECT `中止：admin 之外仍有非BCrypt口令账号，本迁移不会重置它们；请先人工逐个重置后重跑` AS abort_reason');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 增列 PWD_CHANGE_FLAG（已存在则跳过） ----------
SET @has_col := (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
  AND table_name = 'api_employee' AND column_name = 'PWD_CHANGE_FLAG');
SET @sql := IF(@has_col = 1, 'SELECT 1',
  'ALTER TABLE `api_employee` ADD COLUMN `PWD_CHANGE_FLAG` tinyint NOT NULL DEFAULT 1 COMMENT ''是否需强制修改密码1：1否 2是；建号/重置置2，本人改密回1'' AFTER `DISABLED_FLAG`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 存量口令重置为 BCrypt ----------
-- 仅覆盖仍为旧格式（非 $2 前缀）的行；已是 BCrypt 的行（含重复执行）零触碰。
-- 哈希对应演示口令（本体见 client/.env.demo 的 VITE_DEMO_LOGIN_PASSWORD），
-- 与 init/01-base.sql、acc-seed.sql 同源；正式发布前必须重置管理员口令。
UPDATE `api_employee`
   SET `LOGIN_PWD` = '$2a$10$Yfm0okt.fJ0qBFH0JQ5./Ou2Gll6UPxRCtUlr7jLdn2dhkrWRjzzS',
       `PWD_CHANGE_FLAG` = 1
 WHERE `LOGIN_NAME` = 'admin'
   AND NOT (CHAR_LENGTH(`LOGIN_PWD`) = 60
        AND SUBSTRING(`LOGIN_PWD`, 1, 1) = CHAR(36 USING utf8mb4)
        AND SUBSTRING(`LOGIN_PWD`, 2, 1) = '2'
        AND SUBSTRING(`LOGIN_PWD`, 4, 1) = CHAR(36 USING utf8mb4)
        AND SUBSTRING(`LOGIN_PWD`, 7, 1) = CHAR(36 USING utf8mb4));

-- 后置一：不得残留任何非 BCrypt 口令——残留行在新代码下将无法登录（fail-closed）。
-- 同上，用结构判据而非长度判据，且不含 `$` 字面量——被 shell 污染时它照样命中并中止
SET @legacy := (SELECT COUNT(*) FROM `api_employee`
  WHERE NOT (CHAR_LENGTH(`LOGIN_PWD`) = 60
        AND SUBSTRING(`LOGIN_PWD`, 1, 1) = CHAR(36 USING utf8mb4)
        AND SUBSTRING(`LOGIN_PWD`, 2, 1) = '2'
        AND SUBSTRING(`LOGIN_PWD`, 4, 1) = CHAR(36 USING utf8mb4)
        AND SUBSTRING(`LOGIN_PWD`, 7, 1) = CHAR(36 USING utf8mb4)));
SET @sql := IF(@legacy = 0, 'SELECT 1',
  'SELECT `中止：仍有非BCrypt格式口令行；若确认本文件未被shell展开污染，请人工逐个重置后重跑` AS abort_reason');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 后置不变式 ----------
-- col_present 必须 1；legacy_rows 必须 0；admin_bcrypt 必须 1
SELECT
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'api_employee' AND column_name = 'PWD_CHANGE_FLAG') AS col_present,
  (SELECT COUNT(*) FROM `api_employee`
    WHERE NOT (CHAR_LENGTH(`LOGIN_PWD`) = 60
        AND SUBSTRING(`LOGIN_PWD`, 1, 1) = CHAR(36 USING utf8mb4)
        AND SUBSTRING(`LOGIN_PWD`, 2, 1) = '2'
        AND SUBSTRING(`LOGIN_PWD`, 4, 1) = CHAR(36 USING utf8mb4)
        AND SUBSTRING(`LOGIN_PWD`, 7, 1) = CHAR(36 USING utf8mb4))) AS legacy_rows,
  (SELECT COUNT(*) FROM `api_employee`
    WHERE `LOGIN_NAME` = 'admin' AND CHAR_LENGTH(`LOGIN_PWD`) = 60
        AND SUBSTRING(`LOGIN_PWD`, 1, 1) = CHAR(36 USING utf8mb4)
        AND SUBSTRING(`LOGIN_PWD`, 2, 1) = '2'
        AND SUBSTRING(`LOGIN_PWD`, 4, 1) = CHAR(36 USING utf8mb4)
        AND SUBSTRING(`LOGIN_PWD`, 7, 1) = CHAR(36 USING utf8mb4)) AS admin_bcrypt;

SELECT '2026-08-05 迁移完成：员工口令 BCrypt 化，PWD_CHANGE_FLAG 就位' AS RESULT;

-- ============================================================
-- 微信身份注册：手机号改为可后补（2026-08-01）
--
-- 背景：小程序为个人主体、未做微信认证时，微信在**组件层**禁用
-- open-type="getPhoneNumber"——点击不弹窗、回调不触发，服务端拿不到 phoneCode。
-- 于是新用户无法完成「必须绑手机号才能建号」的注册，整条登录链路走不通。
--
-- 本迁移把 USER_PHONE 由 NOT NULL 放开为可空，使「仅微信身份建号、手机号后补」成为可能。
-- 唯一键 uk_user_phone 保留不动：MySQL 唯一索引忽略 NULL，多个未绑手机号的用户可共存；
-- 而空串 '' 之间会互撞唯一键，故应用层必须写 NULL、禁止写空串。
--
-- 与 init/01-base.sql 的列定义逐字一致（双轨）。幂等：重复执行结果相同。
-- ============================================================

SET NAMES utf8mb4;

-- 前置一：核心表必须存在
SET @ok := (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
  AND table_name = 'ws_user');
SET @sql := IF(@ok = 1, 'SELECT 1', 'SELECT `中止：ws_user 缺失`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 前置二：不得存在空串手机号——放开非空后它们会互撞 uk_user_phone
SET @blank := (SELECT COUNT(*) FROM ws_user WHERE USER_PHONE = '');
SET @sql := IF(@blank = 0, 'SELECT 1', 'SELECT `中止：存在空串手机号脏数据，请先改为 NULL 或补齐`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 放开 USER_PHONE 非空约束 ----------
SET @nullable := (SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema = DATABASE()
  AND table_name = 'ws_user' AND column_name = 'USER_PHONE');
SET @sql := IF(@nullable = 'YES', 'SELECT 1',
  'ALTER TABLE `ws_user` MODIFY COLUMN `USER_PHONE` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''用户联系方式；未绑定时为 NULL（唯一键忽略 NULL，多个未绑用户可共存）。禁止写空串——空串会互撞唯一键''');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---------- 后置不变式 ----------
-- phone_nullable 必须 YES；uk_kept 必须 1（去重仍靠唯一键）；blank_rows 必须 0
SELECT
  (SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'ws_user' AND column_name = 'USER_PHONE') AS phone_nullable,
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 'ws_user' AND index_name = 'uk_user_phone') AS uk_kept,
  (SELECT COUNT(*) FROM ws_user WHERE USER_PHONE = '') AS blank_rows;

SELECT '2026-08-01 迁移完成：USER_PHONE 可空，支持仅微信身份建号' AS RESULT;

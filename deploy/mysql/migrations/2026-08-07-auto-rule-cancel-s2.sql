-- ============================================================
-- 自动补货规则「已取消」终态（S2，2026-08-07）
--
-- 背景：规则表原只有 1启用/2停用两态，用户缺少永久终止入口——停用可被恢复，
-- 语义上不是「取消」。新增 3=已取消：终态不可恢复，Worker 扫描与恢复操作
-- 均以 1/2 为精确前态，3 永不匹配。
--
-- 无表结构变更（RULE_STATUS tinyint 容纳新值），仅补字典与列注释。
-- 字典表无业务唯一键，幂等必须用 NOT EXISTS（mysql8 skill「字典与菜单」）。
-- ============================================================

SET NAMES utf8mb4;

INSERT INTO `api_dict_data`(`DICT_CLASS`, `DICT_DEFAULT_FLAG`, `DICT_TYPE`, `DICT_SORT`, `DICT_VALUE`, `DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
  SELECT '1355' AS DICT_TYPE, 3 AS DICT_SORT, 3 AS DICT_VALUE, '已取消' AS DICT_LABEL
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

ALTER TABLE `ws_delivery_auto_rule`
  MODIFY COLUMN `RULE_STATUS` tinyint NOT NULL COMMENT '规则状态(1355)：1启用 2停用 3已取消（终态不可恢复）';

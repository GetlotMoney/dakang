-- ============================================================
-- E2E-06 机主经营与服务 包A：ws_order 站轨聚合索引（幂等迁移）
--
-- 本迁移只做一件事：为机主经营聚合的「站轨」补 (STATION_ID, CREATE_TIME) 复合索引。
-- 订单聚合范围 = (STATION_ID ∈ 名下站) OR (DEVICE_ID ∈ 名下设备)；设备轨已有
-- idx_order_device_time，站轨缺索引会让按站+时间窗查询退化为全表扫描。
-- 无新表、无新列、无新字典、无数据改写——除索引外零变化。
--
-- 幂等：以 information_schema.STATISTICS 判断索引是否已在，重复执行零变化。
-- 执行方式与既有迁移一致：mysql < 本文件；出错即中止（动态 SQL 借 ER_1054 报中文原因）。
-- ============================================================

SET NAMES utf8mb4;

-- 前置：ws_order 表必须存在
SET @t_order := (SELECT COUNT(*) FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'ws_order');
SET @sql := IF(@t_order = 1, 'SELECT 1', 'SELECT `中止：ws_order 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 幂等断点：索引已在即跳过
SET @done_idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_order'
    AND index_name = 'idx_order_station_time');
-- 钉死 INPLACE+LOCK=NONE：MySQL 8 对 InnoDB 二级索引默认即如此，显式声明的价值是
-- 万一优化器意外回退 COPY/锁表时报错中止（fail-loud），而不是在线上静默锁写。
SET @sql := IF(@done_idx > 0, 'SELECT 1',
  'ALTER TABLE `ws_order` ADD INDEX `idx_order_station_time` (`STATION_ID`, `CREATE_TIME`), ALGORITHM=INPLACE, LOCK=NONE');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 后置不变式：索引必须存在
SET @ok_idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'ws_order'
    AND index_name = 'idx_order_station_time');
SET @sql := IF(@ok_idx > 0, 'SELECT ''E2E-06 包A 迁移完成：ws_order 站轨聚合索引'' AS RESULT',
  'SELECT `中止：idx_order_station_time 创建失败`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

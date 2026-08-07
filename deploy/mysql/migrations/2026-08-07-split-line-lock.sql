-- ============================================================
-- 分账比例配置的商品线锁锚表（R1 P1-3，2026-08-07）
--
-- 背景：configCreate 的「读旧配置→内存断点校验→INSERT」不在按商品线串行化的
-- 事务里；uk_split_config_version 只约束 (线,收款方,生效时点)，两个请求改
-- **不同收款方**时互不撞键——各自按旧视图校验通过后双双写入，同线未来断点
-- 合计可超 100%，到点后所有配送签收在分账挂点 fail-closed，配送完成链熔断。
--
-- 本表每商品线恒一行，仅作配置写入事务的行锁锚（SELECT ... FOR UPDATE）：
-- 锁行→读全部版本→校验全部未来断点→INSERT，同线写入串行化且跨实例生效；
-- 不同商品线各锁各行，互不阻塞。无业务数据，行永不更新、永不删除。
--
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE，重复执行零变更。
-- ============================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `ws_split_line_lock` (
  `PRODUCT_LINE` tinyint NOT NULL COMMENT '商品线(1376)：1售水 2配送——每线恒一行，仅作配置写入的行锁锚',
  PRIMARY KEY (`PRODUCT_LINE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='分账比例配置的商品线锁锚表（配置写入串行化，无业务数据）';

INSERT IGNORE INTO `ws_split_line_lock` (`PRODUCT_LINE`) VALUES (1), (2);

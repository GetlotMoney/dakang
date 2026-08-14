-- ============================================================
-- 区域服务商归属载体（分润 V2 前置，2026-08-12）
--
-- 甲方 2026-08-12 给出六方分润，其中「运营中心」按区县/市/省三级。分润 V2 的
-- 计划模型（ws_split_plan_item.ROLE_CODE = REGION_PROVINCE/CITY/COUNTY）早已能
-- 表达这三级的比例，但**全库没有任何地方能回答「这个区县的运营中心是谁」**：
-- 唯一沾边的是 ws_station.STATION_REGION varchar(50) 自由文本，按它匹配等于按
-- 字符串猜行政区划。本迁移补的就是这个载体，**不含任何比例**——比例待甲方书面确认。
--
-- 两件事：
--   1) ws_station 补三个 6 位行政区划码（GB/T 2260），与 ws_mall_warehouse 同口径
--   2) 新增 ws_region_agent：区域 × 时间 → 服务商
--
-- 【为什么区县列叫 DISTRICT_CODE 而枚举叫 COUNTY】
-- 库里已有三张表用 DISTRICT_CODE（ws_mall_warehouse / ws_mall_order / ws_user_address），
-- 而 SplitV2Enum.RegionLevel 已定为 COUNTY 且被计算器的计划项引用。两边都改不动，
-- 故保留两个名字，并在 RegionAgentResolver.codeOf 里把映射钉成一处 + 测试，而不是靠人记住。
--
-- 【边界·必读：本表不决定订单归属】
-- D-406 已定：经营归属按**推荐关系链（血缘）**冻结，跨行政区放设备不改归属，不按地缘重算；
-- SplitCalcInput.regionChain 直接收用户ID，计算器不接收任何行政区字段。
-- 本表只服务 D-407 的**人工分配**台账与运营筛选：后台要把公域水站分给区域服务商时，
-- 得先能回答"这个区归谁负责"。分配动作产生的归属记录才是分润输入，且建立即冻结。
--
-- 【为什么按生效时间版本化，而不是一行改到底】
-- 领地会换人。运营复盘与审计要能回答"三月份洪山区归谁"，直接 UPDATE 会让这段历史永久消失。
-- 故一次换人 = 插一行新版本；查询取 EFFECT_TIME <= 目标时点 的最大一行。
-- AGENT_STATUS=2 的版本行表示「自该时点起该区域无服务商」，这是可表达的空态，
-- 不靠删行——删行会让那段时间退回到更早的版本，等于把已解约的人又接回去。
--
-- 【本迁移不建立任何归属】只建结构。生产上谁是哪个区的服务商属于运营事实，
-- 必须由后台录入并留痕，不能由迁移脚本替甲方决定。
--
-- 幂等：列与表都先探再建；重复执行零变更。
-- 非破坏：只有 CREATE TABLE IF NOT EXISTS 与 ALTER TABLE ADD。
-- ============================================================

SET NAMES utf8mb4;

-- ============ 只读检查区（不做任何变更） ============

-- 1) ws_station 必须存在
SET @tbl := (SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema = DATABASE() AND table_name = 'ws_station');
SET @sql := IF(@tbl = 1, 'SELECT 1', 'SELECT `中止：ws_station 表不存在`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) 三个区划码列若已存在，必须恰为 varchar(6) 可空（异构残留即中止，不带病继续）
SET @cProv := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_station'
                 AND column_name = 'PROVINCE_CODE');
SET @cCity := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_station'
                 AND column_name = 'CITY_CODE');
SET @cDist := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_station'
                 AND column_name = 'DISTRICT_CODE');
SET @cOk := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ws_station'
               AND column_name IN ('PROVINCE_CODE', 'CITY_CODE', 'DISTRICT_CODE')
               AND data_type = 'varchar' AND character_maximum_length = 6
               AND is_nullable = 'YES');
SET @sql := IF(@cProv + @cCity + @cDist = @cOk, 'SELECT 1',
               'SELECT `中止：ws_station 区划码列已存在但类型/长度/可空性不符预期`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) 存量 ws_region_agent 若已存在，必须带版本唯一键，否则「同区域同生效时间只有一行」
--    这条不变式的地基就是空的——中止，人工核对后再迁
SET @rTbl := (SELECT COUNT(*) FROM information_schema.tables
              WHERE table_schema = DATABASE() AND table_name = 'ws_region_agent');
-- 用 COUNT(DISTINCT index_name)：information_schema.statistics 对复合索引是**每列一行**，
-- uk_region_agent_version 有三列即三行，COUNT(*) 会把"存在一个索引"数成 3。
SET @rUk := (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'ws_region_agent'
               AND index_name = 'uk_region_agent_version' AND non_unique = 0);
SET @sql := IF(@rTbl = 0 OR @rUk > 0, 'SELECT 1',
               'SELECT `中止：存量 ws_region_agent 缺 uk_region_agent_version 唯一键`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ============ 变更区（幂等：已存在即跳过） ============

SET @sql := IF(@cProv = 0,
  'ALTER TABLE ws_station ADD COLUMN `PROVINCE_CODE` varchar(6) NULL COMMENT ''省级行政区划码(GB/T 2260)；区域服务商匹配用，STATION_REGION 仅作展示''',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@cCity = 0,
  'ALTER TABLE ws_station ADD COLUMN `CITY_CODE` varchar(6) NULL COMMENT ''市级行政区划码(GB/T 2260)''',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql := IF(@cDist = 0,
  'ALTER TABLE ws_station ADD COLUMN `DISTRICT_CODE` varchar(6) NULL COMMENT ''区县级行政区划码(GB/T 2260)；对应 SplitV2Enum.RegionLevel.COUNTY，映射见 RegionAgentResolver.codeOf''',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 索引与 init（02-ws-business.sql）/领域源（ws_station.sql）同轨；早期版本漏建，按存在性守卫补齐
SET @iDist := (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ws_station'
                 AND index_name = 'idx_station_district');
SET @sql := IF(@iDist = 0,
  'ALTER TABLE ws_station ADD INDEX `idx_station_district` (`DISTRICT_CODE`)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

CREATE TABLE IF NOT EXISTS `ws_region_agent` (
  `ID`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`   tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`     bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`   varchar(14)  NOT NULL COMMENT '创建时间yyyyMMddHHmmss',
  `UPDATE_BY`     bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`   varchar(14)  NOT NULL COMMENT '更新时间yyyyMMddHHmmss',
  `REGION_LEVEL`  varchar(16)  NOT NULL COMMENT '区域层级：PROVINCE/CITY/COUNTY，与 SplitV2Enum.RegionLevel 同源（COUNTY 对应 ws_station.DISTRICT_CODE）',
  `REGION_CODE`   varchar(6)   NOT NULL COMMENT '行政区划码(GB/T 2260)，与 ws_station 上同层级的码等值匹配',
  `REGION_NAME`   varchar(50)  NOT NULL COMMENT '区域名称，仅供排障与后台展示，绝不参与匹配',
  `AGENT_USER_ID` bigint       NOT NULL COMMENT '服务商用户ID(ws_user.ID)；本表是领地登记，不决定订单归属（D-406 归属按血缘冻结）',
  `AGENT_STATUS`  tinyint      NOT NULL COMMENT '状态：1生效 2停用；停用行表示自 EFFECT_TIME 起该区域无服务商',
  `EFFECT_TIME`   varchar(14)  NOT NULL COMMENT '生效时间（含）yyyyMMddHHmmss；按订单创建时点选版本，变更不追溯',
  `AGENT_REMARK`  varchar(200) DEFAULT NULL COMMENT '备注(max200)：换签原因、合同号等',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_region_agent_version` (`REGION_LEVEL`, `REGION_CODE`, `EFFECT_TIME`),
  KEY `idx_region_agent_lookup` (`REGION_LEVEL`, `REGION_CODE`, `EFFECT_TIME`),
  KEY `idx_region_agent_user` (`AGENT_USER_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='区域服务商归属（版本化）：区域×生效时间→服务商，不含任何分润比例';

-- 终检：三列、区划索引与表必须同时就位
SET @final := (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'ws_station'
                 AND column_name IN ('PROVINCE_CODE', 'CITY_CODE', 'DISTRICT_CODE'))
            + (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ws_station'
                 AND index_name = 'idx_station_district')
            + (SELECT COUNT(*) FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'ws_region_agent')
            + (SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'ws_region_agent'
                 AND index_name = 'uk_region_agent_version' AND non_unique = 0);
SET @sql := IF(@final = 6, 'SELECT ''区域服务商载体迁移完成''', 'SELECT `中止：终检失败`');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

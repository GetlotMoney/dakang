-- ============================================================
-- E2E-09 商城 S1：商品/前置仓/库存基座（2026-08-08）
--
-- 六表（分类/SPU/SKU/前置仓/库存/只增流水）+ 字典 1388~1392 与 1363#11
-- + 演示种子 + 商城管理菜单权限。契约：docs/contracts/E2E-09-mall-contract.md。
-- 幂等：CREATE TABLE IF NOT EXISTS / 字典 NOT EXISTS / 显式 ID 种子与菜单
-- INSERT IGNORE / 角色绑定 NOT EXISTS。无 DROP/TRUNCATE/无 WHERE 改删。
-- 本轮仅独立临时库验证，主库执行须单独授权。
-- ============================================================

SET NAMES utf8mb4;


-- ----------------------------
-- 商品分类
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_category` (
  `ID`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`        bigint       NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`      varchar(14)  NOT NULL COMMENT '创建时间',
  `UPDATE_BY`        bigint       NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`      varchar(14)  NOT NULL COMMENT '更新时间',
  `CATEGORY_CODE`    varchar(50)  NOT NULL COMMENT '分类业务编码(max50)：唯一，逻辑删除后不复用',
  `CATEGORY_NAME`    varchar(50)  NOT NULL COMMENT '分类名称(max50)',
  `CATEGORY_SORT`    int          NOT NULL DEFAULT 0 COMMENT '排序号：小在前',
  `CATEGORY_STATUS`  tinyint      NOT NULL COMMENT '分类状态(1392)：1启用 2停用；停用分类下商品不得新上架',
  PRIMARY KEY (`ID`),
  -- 刻意不含 DATA_STATUS：编码是对外业务标识，删除后复用会让历史追溯串号
  UNIQUE KEY `uk_mall_category_code` (`CATEGORY_CODE`),
  INDEX `idx_mall_category_status` (`CATEGORY_STATUS`, `CATEGORY_SORT`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城商品分类表（E2E-09 S1）';

-- ----------------------------
-- 商品 SPU：展示主体；定价与库存在 SKU
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_product` (
  `ID`                bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`       tinyint       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`         bigint        NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`       varchar(14)   NOT NULL COMMENT '创建时间',
  `UPDATE_BY`         bigint        NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`       varchar(14)   NOT NULL COMMENT '更新时间',
  `PRODUCT_NO`        varchar(50)   NOT NULL COMMENT '商品业务编号(max50)：唯一，不复用',
  `CATEGORY_ID`       bigint        NOT NULL COMMENT '分类ID（ws_mall_category.ID）',
  `PRODUCT_NAME`      varchar(100)  NOT NULL COMMENT '商品名称(max100)',
  `PRODUCT_SUBTITLE`  varchar(200)  COMMENT '副标题(max200)',
  `COVER_URL`         varchar(500)  COMMENT '主图（既有合法 URL/相对资源路径；本期无上传能力）',
  `PRODUCT_DESC`      text          COMMENT '商品说明（纯文本/受控富文本，本期纯文本展示）',
  `PRODUCT_STATUS`    tinyint       NOT NULL COMMENT '商品状态(1388)：1草稿 2已上架 3已下架；上架前置校验见契约四',
  `VERSION`           int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本：上下架 CAS 用',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_product_no` (`PRODUCT_NO`),
  INDEX `idx_mall_product_category` (`CATEGORY_ID`, `PRODUCT_STATUS`),
  INDEX `idx_mall_product_status` (`PRODUCT_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城商品SPU表（E2E-09 S1）';

-- ----------------------------
-- SKU：定价与库存主体
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_sku` (
  `ID`            bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`   tinyint       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除；被库存/订单引用的 SKU 禁物理删除',
  `CREATE_BY`     bigint        NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`   varchar(14)   NOT NULL COMMENT '创建时间',
  `UPDATE_BY`     bigint        NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`   varchar(14)   NOT NULL COMMENT '更新时间',
  `SKU_NO`        varchar(50)   NOT NULL COMMENT 'SKU业务编号(max50)：唯一，不复用',
  `PRODUCT_ID`    bigint        NOT NULL COMMENT '商品ID（ws_mall_product.ID）',
  `SKU_NAME`      varchar(100)  NOT NULL COMMENT 'SKU名称(max100)',
  `SPEC_SNAP`     varchar(500)  NOT NULL COMMENT '规格快照 JSON(max500)：扁平 string→string 对象（如 {"规格":"500ml×12","口味":"原味"}），≤8键，服务端唯一校验；owner=商城域；演进只增键不改语义',
  `SALE_PRICE`    bigint        NOT NULL COMMENT '售价(分)：正整数',
  `MARKET_PRICE`  bigint        COMMENT '划线价(分)：可空；有值时 ≥ SALE_PRICE',
  `WEIGHT_GRAM`   bigint        NOT NULL DEFAULT 0 COMMENT '重量(克)：非负整数',
  `SKU_STATUS`    tinyint       NOT NULL COMMENT 'SKU状态(1389)：1启用 2停用；停用不删历史库存与流水',
  `VERSION`       int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_sku_no` (`SKU_NO`),
  INDEX `idx_mall_sku_product` (`PRODUCT_ID`, `SKU_STATUS`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城SKU表（E2E-09 S1）';

-- ----------------------------
-- 前置仓
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_warehouse` (
  `ID`                 bigint        NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`        tinyint       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1删除',
  `CREATE_BY`          bigint        NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`        varchar(14)   NOT NULL COMMENT '创建时间',
  `UPDATE_BY`          bigint        NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`        varchar(14)   NOT NULL COMMENT '更新时间',
  `WAREHOUSE_NO`       varchar(50)   NOT NULL COMMENT '前置仓业务编号(max50)：唯一，不复用',
  `WAREHOUSE_NAME`     varchar(100)  NOT NULL COMMENT '前置仓名称(max100)',
  `CONTACT_NAME`       varchar(50)   NOT NULL COMMENT '联系人(max50)',
  `CONTACT_PHONE`      varchar(20)   NOT NULL COMMENT '联系电话(max20)：列表/详情脱敏展示，原文不进小程序响应',
  `PROVINCE_CODE`      varchar(6)    NOT NULL COMMENT '省行政区码(6位)',
  `CITY_CODE`          varchar(6)    NOT NULL COMMENT '市行政区码(6位)',
  `DISTRICT_CODE`      varchar(6)    NOT NULL COMMENT '区行政区码(6位)',
  `WAREHOUSE_ADDRESS`  varchar(200)  NOT NULL COMMENT '详细地址(max200)',
  `LONGITUDE`          varchar(20)   COMMENT '经度（沿用水站合同字符串精度，接真地图前不改型）',
  `LATITUDE`           varchar(20)   COMMENT '纬度（同上）',
  `SERVICE_SCOPE_JSON` varchar(1000) NOT NULL COMMENT '履约范围 JSON(max1000)：{"scopeType":"districts","districtCodes":["420111"]}；服务端唯一校验（六位码/非空/去重）；owner=商城域；S2 履约按此过滤；原文不下发小程序',
  `WAREHOUSE_STATUS`   tinyint       NOT NULL COMMENT '前置仓状态(1390)：1启用 2停用；停用仓不参与小程序可售聚合',
  `VERSION`            int           NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
  PRIMARY KEY (`ID`),
  UNIQUE KEY `uk_mall_warehouse_no` (`WAREHOUSE_NO`),
  INDEX `idx_mall_warehouse_status` (`WAREHOUSE_STATUS`),
  INDEX `idx_mall_warehouse_district` (`DISTRICT_CODE`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城前置仓表（E2E-09 S1）';

-- ----------------------------
-- 库存：仓×SKU 一行；一切扣减原子条件 UPDATE
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_stock` (
  `ID`             bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`    tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：库存行恒0，不做业务逻辑删除',
  `CREATE_BY`      bigint      NOT NULL COMMENT '创建人ID',
  `CREATE_TIME`    varchar(14) NOT NULL COMMENT '创建时间',
  `UPDATE_BY`      bigint      NOT NULL COMMENT '更新人ID',
  `UPDATE_TIME`    varchar(14) NOT NULL COMMENT '更新时间',
  `WAREHOUSE_ID`   bigint      NOT NULL COMMENT '前置仓ID（ws_mall_warehouse.ID）',
  `SKU_ID`         bigint      NOT NULL COMMENT 'SKU ID（ws_mall_sku.ID）',
  `AVAILABLE_QTY`  bigint      NOT NULL DEFAULT 0 COMMENT '可售数量(件)：恒≥0；扣减必须原子条件 UPDATE（WHERE AVAILABLE_QTY>=q），禁止读出后内存计算回写',
  `RESERVED_QTY`   bigint      NOT NULL DEFAULT 0 COMMENT '预占数量(件)：恒≥0；本期恒0，S2 下单预占启用（AVAILABLE→RESERVED 原子迁移）',
  `VERSION`        int         NOT NULL DEFAULT 1 COMMENT '乐观锁版本：随每次库存动作+1',
  PRIMARY KEY (`ID`),
  -- 并发安全唯一锚：同仓同 SKU 恒一行，确保原子 UPDATE 定位唯一
  UNIQUE KEY `uk_mall_stock_wh_sku` (`WAREHOUSE_ID`, `SKU_ID`),
  INDEX `idx_mall_stock_sku` (`SKU_ID`),
  -- 负库存库层硬闸（R1-P2-1）：条件 UPDATE 之外的任何原始写入同样不可为负
  CONSTRAINT `chk_mall_stock_available_nonneg` CHECK (`AVAILABLE_QTY` >= 0),
  CONSTRAINT `chk_mall_stock_reserved_nonneg` CHECK (`RESERVED_QTY` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城库存表（仓×SKU，E2E-09 S1）';

-- ----------------------------
-- 库存流水：只增不改；幂等键=库层闸
-- ----------------------------
CREATE TABLE IF NOT EXISTS `ws_mall_stock_flow` (
  `ID`                  bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `DATA_STATUS`         tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：流水恒0；即使被误标删除，幂等键也不复用（唯一键不含本列）',
  `CREATE_BY`           bigint       NOT NULL COMMENT '创建人ID（=操作人）',
  `CREATE_TIME`         varchar(14)  NOT NULL COMMENT '创建时间（动作时刻）',
  `UPDATE_BY`           bigint       NOT NULL COMMENT '更新人ID（只增表，恒=创建人）',
  `UPDATE_TIME`         varchar(14)  NOT NULL COMMENT '更新时间（只增表，恒=创建时间）',
  `BIZ_IDEMPOTENCY_KEY` varchar(64)  NOT NULL COMMENT '幂等键(max64)：人工动作=MALLADJ:<requestId>；S2 起预占/实销/释放/回库=MALLRSV:<orderNo> 族',
  `WAREHOUSE_ID`        bigint       NOT NULL COMMENT '前置仓ID',
  `SKU_ID`              bigint       NOT NULL COMMENT 'SKU ID',
  `FLOW_TYPE`           tinyint      NOT NULL COMMENT '流水类型(1391)：1人工入库 2人工出库 3盘点调增 4盘点调减；5~8 预留（预占/释放/实销/退货回库）',
  `AVAILABLE_CHANGE`    bigint       NOT NULL COMMENT '可售变化量(件)：带符号',
  `RESERVED_CHANGE`     bigint       NOT NULL COMMENT '预占变化量(件)：带符号；本期恒0',
  `AVAILABLE_AFTER`     bigint       NOT NULL COMMENT '动作后可售终值(件)：与库存行同事务一致',
  `RESERVED_AFTER`      bigint       NOT NULL COMMENT '动作后预占终值(件)',
  `FLOW_REASON`         varchar(200) NOT NULL COMMENT '动作原因(max200)：必填',
  `OPERATOR_ID`         bigint       NOT NULL COMMENT '操作人ID（管理端账号）',
  PRIMARY KEY (`ID`),
  -- 刻意不含 DATA_STATUS：幂等键是资金级防重锚，逻辑删除不解锁重放
  UNIQUE KEY `uk_mall_stock_flow_biz_key` (`BIZ_IDEMPOTENCY_KEY`),
  INDEX `idx_mall_stock_flow_wh_sku` (`WAREHOUSE_ID`, `SKU_ID`),
  INDEX `idx_mall_stock_flow_sku` (`SKU_ID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商城库存流水表（只增，E2E-09 S1）';

-- ----------------------------
-- 字典（1388~1392 + 1363 增值 11 商城动作）
-- ----------------------------
INSERT INTO `api_dict_type`(`DICT_NAME`, `DICT_TYPE`, `DICT_REMARK`)
SELECT s.* FROM (
            SELECT '商城商品状态' AS DICT_NAME, '1388' AS DICT_TYPE, '商城SPU上下架状态' AS DICT_REMARK
  UNION ALL SELECT '商城SKU状态', '1389', '商城SKU启停状态'
  UNION ALL SELECT '前置仓状态', '1390', '商城前置仓启停状态'
  UNION ALL SELECT '商城库存流水类型', '1391', '商城库存动作类型（5~8预留S2）'
  UNION ALL SELECT '商城分类状态', '1392', '商城商品分类启停状态'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_type t WHERE t.DICT_TYPE = s.DICT_TYPE);

INSERT INTO `api_dict_data`(`DICT_CLASS`,`DICT_DEFAULT_FLAG`,`DICT_TYPE`,`DICT_SORT`,`DICT_VALUE`,`DICT_LABEL`)
SELECT NULL, 1, s.DICT_TYPE, s.DICT_SORT, s.DICT_VALUE, s.DICT_LABEL FROM (
            SELECT '1388' AS DICT_TYPE, 1 AS DICT_SORT, 1 AS DICT_VALUE, '草稿' AS DICT_LABEL
  UNION ALL SELECT '1388', 2, 2, '已上架'
  UNION ALL SELECT '1388', 3, 3, '已下架'
  UNION ALL SELECT '1389', 1, 1, '启用'
  UNION ALL SELECT '1389', 2, 2, '停用'
  UNION ALL SELECT '1390', 1, 1, '启用'
  UNION ALL SELECT '1390', 2, 2, '停用'
  UNION ALL SELECT '1391', 1, 1, '人工入库'
  UNION ALL SELECT '1391', 2, 2, '人工出库'
  UNION ALL SELECT '1391', 3, 3, '盘点调增'
  UNION ALL SELECT '1391', 4, 4, '盘点调减'
  UNION ALL SELECT '1392', 1, 1, '启用'
  UNION ALL SELECT '1392', 2, 2, '停用'
  UNION ALL SELECT '1363', 11, 11, '商城动作'
) s
WHERE NOT EXISTS (SELECT 1 FROM api_dict_data d WHERE d.DICT_TYPE = s.DICT_TYPE AND d.DICT_VALUE = s.DICT_VALUE);

-- ----------------------------
-- 演示种子（覆盖草稿/上架/下架/缺货/停用仓/停用SKU/停用分类；假手机号）
-- 追溯关系：库存终值与流水 AFTER 自洽（见 ws_mall_stock_flow 种子）
-- ----------------------------
INSERT IGNORE INTO `ws_mall_category`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`CATEGORY_CODE`,`CATEGORY_NAME`,`CATEGORY_SORT`,`CATEGORY_STATUS`) VALUES
(1,0,1,'20260808120000',1,'20260808120000','MC-DRINK','包装饮用水',1,1),
(2,0,1,'20260808120000',1,'20260808120000','MC-DEVICE','净水耗材',2,1),
(3,0,1,'20260808120000',1,'20260808120000','MC-GIFT','礼品周边',3,2);

INSERT IGNORE INTO `ws_mall_product`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`PRODUCT_NO`,`CATEGORY_ID`,`PRODUCT_NAME`,`PRODUCT_SUBTITLE`,`COVER_URL`,`PRODUCT_DESC`,`PRODUCT_STATUS`,`VERSION`) VALUES
(1,0,1,'20260808120000',1,'20260808120000','MP-2026-0001',1,'六维达康天然饮用水 整箱','500ml×12 瓶装 家庭常备',NULL,'水源地直供，整箱配送到家。',2,2),
(2,0,1,'20260808120000',1,'20260808120000','MP-2026-0002',1,'六维达康桶装水 18.9L','空桶可换 押金另计',NULL,'18.9L 家用桶装水，前置仓就近履约。',2,2),
(3,0,1,'20260808120000',1,'20260808120000','MP-2026-0003',2,'家用净水器滤芯 PP棉','三个月更换周期',NULL,'适配六维达康家用净水系列。',2,2),
(4,0,1,'20260808120000',1,'20260808120000','MP-2026-0004',2,'净水器复合滤芯 RO膜','高端机型适配',NULL,'RO 反渗透复合滤芯（草稿示例）。',1,1),
(5,0,1,'20260808120000',1,'20260808120000','MP-2026-0005',3,'达康定制保温杯','曾上架现已下架',NULL,'316 不锈钢保温杯（已下架示例）。',3,3);

-- 早期候选脚本曾写入并不存在于小程序包内的 /static/mall 占位图。仅收敛这五个固定
-- 种子编号及该旧前缀，避免影响运营后续配置的真实图片地址；重复执行保持幂等。
UPDATE `ws_mall_product`
SET `COVER_URL` = NULL, `UPDATE_BY` = 1, `UPDATE_TIME` = '20260808120000'
WHERE `PRODUCT_NO` IN ('MP-2026-0001','MP-2026-0002','MP-2026-0003','MP-2026-0004','MP-2026-0005')
  AND `COVER_URL` LIKE '/static/mall/%';

INSERT IGNORE INTO `ws_mall_sku`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`SKU_NO`,`PRODUCT_ID`,`SKU_NAME`,`SPEC_SNAP`,`SALE_PRICE`,`MARKET_PRICE`,`WEIGHT_GRAM`,`SKU_STATUS`,`VERSION`) VALUES
(1,0,1,'20260808120000',1,'20260808120000','MS-2026-0001',1,'整箱 500ml×12','{"规格":"500ml×12"}',2400,2990,6600,1,1),
(2,0,1,'20260808120000',1,'20260808120000','MS-2026-0002',1,'整箱 500ml×24','{"规格":"500ml×24"}',4500,5800,13200,1,1),
(3,0,1,'20260808120000',1,'20260808120000','MS-2026-0003',2,'单桶 18.9L','{"规格":"18.9L"}',1800,NULL,19500,1,1),
(4,0,1,'20260808120000',1,'20260808120000','MS-2026-0004',2,'五桶套票 18.9L×5','{"规格":"18.9L×5","形态":"套票"}',8500,9000,0,2,1),
(5,0,1,'20260808120000',1,'20260808120000','MS-2026-0005',3,'PP棉滤芯 单支','{"规格":"单支","适配":"家用一代"}',3900,4500,350,1,1),
(6,0,1,'20260808120000',1,'20260808120000','MS-2026-0006',3,'PP棉滤芯 三支装','{"规格":"三支装","适配":"家用一代"}',9900,13500,1050,1,1),
(7,0,1,'20260808120000',1,'20260808120000','MS-2026-0007',4,'RO膜滤芯 单支','{"规格":"单支","适配":"旗舰机"}',19900,25900,900,1,1),
(8,0,1,'20260808120000',1,'20260808120000','MS-2026-0008',5,'保温杯 500ml 银','{"容量":"500ml","颜色":"银"}',6900,8900,320,1,1);

INSERT IGNORE INTO `ws_mall_warehouse`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`WAREHOUSE_NO`,`WAREHOUSE_NAME`,`CONTACT_NAME`,`CONTACT_PHONE`,`PROVINCE_CODE`,`CITY_CODE`,`DISTRICT_CODE`,`WAREHOUSE_ADDRESS`,`LONGITUDE`,`LATITUDE`,`SERVICE_SCOPE_JSON`,`WAREHOUSE_STATUS`,`VERSION`) VALUES
(1,0,1,'20260808120000',1,'20260808120000','MW-WH-001','光谷前置仓','周仓管','13500005555','420000','420100','420111','武汉市洪山区光谷大道 77 号','114.4180','30.4680','{"scopeType":"districts","districtCodes":["420111","420114"]}',1,1),
(2,0,1,'20260808120000',1,'20260808120000','MW-WH-002','汉口前置仓（停用示例）','吴仓管','13500006666','420000','420100','420102','武汉市江岸区解放大道 1001 号','114.3050','30.5960','{"scopeType":"districts","districtCodes":["420102"]}',2,1),
(3,0,1,'20260808120000',1,'20260808120000','MW-WH-003','南湖前置仓','郑仓管','13500007777','420000','420100','420111','武汉市洪山区南湖大道 12 号','114.3420','30.4880','{"scopeType":"districts","districtCodes":["420111"]}',1,1);

-- 库存种子：SKU1 双仓有货；SKU2 仅停用仓有货（端上应显缺货）；SKU3 有货；
-- SKU5 有货、SKU6 零库存（缺货示例）；SKU7/8（草稿/下架商品）留少量库存不影响端上
INSERT IGNORE INTO `ws_mall_stock`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`WAREHOUSE_ID`,`SKU_ID`,`AVAILABLE_QTY`,`RESERVED_QTY`,`VERSION`) VALUES
(1,0,1,'20260808120000',1,'20260808120000',1,1,120,0,2),
(2,0,1,'20260808120000',1,'20260808120000',3,1,45,0,2),
(3,0,1,'20260808120000',1,'20260808120000',2,2,60,0,2),
(4,0,1,'20260808120000',1,'20260808120000',1,3,200,0,3),
(5,0,1,'20260808120000',1,'20260808120000',1,5,80,0,2),
(6,0,1,'20260808120000',1,'20260808120000',3,6,0,0,3),
(7,0,1,'20260808120000',1,'20260808120000',1,7,10,0,2),
(8,0,1,'20260808120000',1,'20260808120000',1,8,15,0,2);

-- 流水种子：每行库存的建行入库轨迹，AFTER 与上表终值一致（SKU3 含一次出库、SKU6 出清）
INSERT IGNORE INTO `ws_mall_stock_flow`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`BIZ_IDEMPOTENCY_KEY`,`WAREHOUSE_ID`,`SKU_ID`,`FLOW_TYPE`,`AVAILABLE_CHANGE`,`RESERVED_CHANGE`,`AVAILABLE_AFTER`,`RESERVED_AFTER`,`FLOW_REASON`,`OPERATOR_ID`) VALUES
(1,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0001',1,1,1,120,0,120,0,'首批入库',1),
(2,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0002',3,1,1,45,0,45,0,'首批入库',1),
(3,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0003',2,2,1,60,0,60,0,'首批入库（停用仓示例）',1),
(4,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0004',1,3,1,220,0,220,0,'首批入库',1),
(5,0,1,'20260808120200',1,'20260808120200','MALLADJ:seed-0005',1,3,2,-20,0,200,0,'破损出库',1),
(6,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0006',1,5,1,80,0,80,0,'首批入库',1),
(7,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0007',3,6,1,30,0,30,0,'首批入库',1),
(8,0,1,'20260808120200',1,'20260808120200','MALLADJ:seed-0008',3,6,2,-30,0,0,0,'临期出清',1),
(9,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0009',1,7,1,10,0,10,0,'样品入库',1),
(10,0,1,'20260808120100',1,'20260808120100','MALLADJ:seed-0010',1,8,1,15,0,15,0,'尾货入库',1);

-- ----------------------------
-- 菜单与权限（ID/权限码与 deploy/mysql/init/03-demo-baseline.sql 逐字一致）
-- ----------------------------
INSERT IGNORE INTO `api_rbac_menu`
(`ID`,`MENU_NAME`,`MENU_TYPE`,`MENU_ICON`,`MENU_PARENT_ID`,`MENU_SORT`,`MENU_PATH`,`MENU_COMPONENT`,`MENU_FRAME_FLAG`,`MENU_API_PERMS`,`MENU_WEB_PERMS`,`MENU_VISIBLE_FLAG`,`MENU_DISABLED_FLAG`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`)
VALUES
(1005,'商城管理',1,'ri:store-2-line',0,82,'/mall','/index/index',1,NULL,NULL,1,1,0,1,'20260808120000',1,'20260808120000'),
(1036,'商品管理',2,NULL,1005,4,'product','/mall/product',1,NULL,NULL,1,1,0,1,'20260808120000',1,'20260808120000'),
(1037,'分类管理',2,NULL,1005,3,'category','/mall/category',1,NULL,NULL,1,1,0,1,'20260808120000',1,'20260808120000'),
(1038,'前置仓管理',2,NULL,1005,2,'warehouse','/mall/warehouse',1,NULL,NULL,1,1,0,1,'20260808120000',1,'20260808120000'),
(1039,'库存管理',2,NULL,1005,1,'stock','/mall/stock',1,NULL,NULL,1,1,0,1,'20260808120000',1,'20260808120000'),
(1151,'分类维护',3,NULL,1037,1,NULL,NULL,1,'mall:category:edit','mall:category:edit',2,1,0,1,'20260808120000',1,'20260808120000'),
(1152,'商品维护',3,NULL,1036,1,NULL,NULL,1,'mall:product:edit','mall:product:edit',2,1,0,1,'20260808120000',1,'20260808120000'),
(1153,'商品上下架',3,NULL,1036,2,NULL,NULL,1,'mall:product:shelf','mall:product:shelf',2,1,0,1,'20260808120000',1,'20260808120000'),
(1154,'前置仓维护',3,NULL,1038,1,NULL,NULL,1,'mall:warehouse:edit','mall:warehouse:edit',2,1,0,1,'20260808120000',1,'20260808120000'),
(1155,'库存调整',3,NULL,1039,1,NULL,NULL,1,'mall:stock:adjust','mall:stock:adjust',2,1,0,1,'20260808120000',1,'20260808120000');

INSERT IGNORE INTO `api_rbac_role_menu`
(`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`DATA_STATUS`,`ROLE_ID`,`MENU_ID`)
SELECT 1,'20260808120000',1,'20260808120000',0,1,m.`ID`
FROM `api_rbac_menu` m
WHERE m.`ID` IN (1005,1036,1037,1038,1039,1151,1152,1153,1154,1155)
  AND NOT EXISTS (
    SELECT 1 FROM `api_rbac_role_menu` rm
    WHERE rm.`ROLE_ID` = 1 AND rm.`MENU_ID` = m.`ID`
  );

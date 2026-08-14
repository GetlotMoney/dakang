-- ============================================================
-- 六维达康 · 水卡生命周期验收种子（唯一一份，幂等，可重复执行）
--
-- 只允许灌入独立验收库 dakang-acc-mysql（宿主端口 3309）；由 deploy/acceptance/acc-env.sh
-- 在「init SQL + migrations 全部执行完毕」后灌入。禁止对主库（3308）执行本文件。
--
-- ID 统一使用 9xxx 命名空间，与 01/02/03 演示种子（ID 1~5 区段）零交集：
--   9001/9002  ws_user       无卡卡主 / 成员账号
--   9001       api_employee  运营账号 acc-ops（复用 admin 的 RSA 口令密文，绑定超管角色 1）
--   9101/9102  ws_station    验收水站1 / 验收水站2
--   9201/9202  ws_device     ACC-DEV-0001（站1）/ ACC-DEV-0002（站2），均在线空闲
--   9301/9302  ws_device_outlet  各站 1 号出水口（纯净水，20分/升）
--   9401/9402  ws_qrcode     ACC-QR-DEV1-O1 / ACC-QR-DEV2-O1
--   9501       ws_package    验收套餐（SCOPE_JSON 仅允许水站 9101——S1 可购与 S6 范围外拒绝的前提）
--
-- 刻意不为 9001 预置任何 ws_card：S1 首次购卡要求「无卡用户」。
-- ============================================================
SET NAMES utf8mb4;
USE dakang;

-- 运营账号（S5/S8 经 PC /user/card/changeStatus 冻结解冻用）。
-- LOGIN_PWD 为 BCrypt 单向哈希（R-201），与 01-base 的 admin 同一演示口令
-- （本体见 client/.env.demo 的 VITE_DEMO_LOGIN_PASSWORD），不引入新明文口令。
-- e2e 的 ACC_OPS_PWD_CIPHER 仍是**传输层** RSA 密文：服务端解密得到明文后与本哈希比对，
-- 与存储格式解耦；更换演示口令时需同步更新本哈希与 ACC_OPS_PWD_CIPHER 两处。
-- PWD_CHANGE_FLAG 走列默认 1（无需强改）：验收自动化依赖这两个账号直接登录。
INSERT IGNORE INTO `api_employee`
(`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `LOGIN_NAME`, `LOGIN_PWD`, `EMPLOYEE_NAME`, `EMPLOYEE_GENDER`, `EMPLOYEE_PHONE`, `DEPT_ID`, `POSITION_ID`, `DISABLED_FLAG`) VALUES
(9001, 1, '20260723000000', 1, '20260723000000', 0, 'acc-ops', '$2a$10$Yfm0okt.fJ0qBFH0JQ5./Ou2Gll6UPxRCtUlr7jLdn2dhkrWRjzzS', '验收运营', 1, '13999990009', 1, 1, 1);

INSERT IGNORE INTO `api_rbac_role_employee`
(`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `ROLE_ID`, `EMPLOYEE_ID`) VALUES
(9001, 1, '20260723000000', 1, '20260723000000', 0, 1, 9001);

-- E2E-05 S12 换人拒绝专用：第二运营账号（同一演示口令的 BCrypt 哈希，绑定同一超管角色）——
-- 高风险控制 ticket 绑定预览人，acc-ops2 领取 acc-ops 的 ticket 必须被拒
INSERT IGNORE INTO `api_employee`
(`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `LOGIN_NAME`, `LOGIN_PWD`, `EMPLOYEE_NAME`, `EMPLOYEE_GENDER`, `EMPLOYEE_PHONE`, `DEPT_ID`, `POSITION_ID`, `DISABLED_FLAG`) VALUES
(9002, 1, '20260723000000', 1, '20260723000000', 0, 'acc-ops2', '$2a$10$Yfm0okt.fJ0qBFH0JQ5./Ou2Gll6UPxRCtUlr7jLdn2dhkrWRjzzS', '验收运营乙', 1, '13999990010', 1, 1, 1);

INSERT IGNORE INTO `api_rbac_role_employee`
(`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `ROLE_ID`, `EMPLOYEE_ID`) VALUES
(9002, 1, '20260723000000', 1, '20260723000000', 0, 1, 9002);

-- C 端账号：9001 无卡卡主（S1 起点），9002 成员（S9 授权对象）
INSERT IGNORE INTO `ws_user`
(`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `USER_NAME`, `USER_GENDER`, `USER_PHONE`, `USER_IDENTITY_CIPHER`, `USER_AVATAR`, `DISABLED_FLAG`, `USER_STATUS`, `POINTS`, `WECHAT_XCX_OPENID`, `CHANNEL_USER_ID`, `REFERRER_USER_ID`, `PROMO_CODE`) VALUES
(9001, 1, '20260723000000', 1, '20260723000000', 0, '验收卡主', 1, '13999990001', 'acc-cipher-9001', NULL, 1, 1, 0, NULL, NULL, NULL, NULL),
(9002, 1, '20260723000000', 1, '20260723000000', 0, '验收成员', 2, '13999990002', 'acc-cipher-9002', NULL, 1, 1, 0, NULL, NULL, NULL, NULL);

-- 两个验收水站：9101 在套餐范围内，9102 用于 S6 范围外拒绝
INSERT IGNORE INTO `ws_station`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `STATION_NAME`, `STATION_CODE`, `STATION_REGION`, `STATION_ADDRESS`, `STATION_LNG`, `STATION_LAT`, `STATION_STATUS`, `OWNER_USER_ID`, `CHANNEL_USER_ID`, `STATION_REMARK`) VALUES
(9101, 0, 1, '20260723000000', 1, '20260723000000', '验收水站1', 'WS-ACC-001', '验收专区', '验收环境水站1（套餐范围内）', NULL, NULL, 1, 9001, NULL, '验收专用（E2E-06 站轨归属：机主 9001 同时拥有站 9101 与设备 9201，双轨去重实例）'),
(9102, 0, 1, '20260723000000', 1, '20260723000000', '验收水站2', 'WS-ACC-002', '验收专区', '验收环境水站2（范围外拒绝用）', NULL, NULL, 1, NULL, NULL, '水卡生命周期验收专用');

-- 两台设备均在线空闲：S6 的拒绝必须由「卡范围」给出，而不是设备离线/故障抢先拒绝
INSERT IGNORE INTO `ws_device`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DEVICE_NO`, `DEVICE_NAME`, `DEVICE_MODEL`, `STATION_ID`, `OWNER_USER_ID`, `FIRMWARE_VERSION`, `SIM_ICCID`, `SIM_CARRIER`, `ONLINE_STATUS`, `RUN_STATUS`, `LAST_HEARTBEAT`, `LAST_FAULT_CODE`, `SIGNAL_STRENGTH`, `DEVICE_REMARK`) VALUES
(9201, 0, 1, '20260723000000', 1, '20260723000000', 'ACC-DEV-0001', '验收1号机', 'DK-W800', 9101, 9001, 'v1.0.0', 'ACCICCID000000000001', '模拟运营商', 1, 1, '20260723000000', NULL, -60, '验收专用（tools/device-sim 扮演；E2E-05 归属机主 9001）'),
(9202, 0, 1, '20260723000000', 1, '20260723000000', 'ACC-DEV-0002', '验收2号机', 'DK-W800', 9102, NULL, 'v1.0.0', NULL, NULL, 1, 1, '20260723000000', NULL, -60, '水卡生命周期验收专用（仅供范围外拒绝，不接模拟器）');

INSERT IGNORE INTO `ws_device_outlet`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DEVICE_ID`, `OUTLET_NO`, `WATER_TYPE_ID`, `WATER_TYPE`, `OUTLET_PRICE`, `OUTLET_STATUS`) VALUES
(9301, 0, 1, '20260723000000', 1, '20260723000000', 9201, 1, 1, '纯净水', '20', 1),
(9302, 0, 1, '20260723000000', 1, '20260723000000', 9202, 1, 1, '纯净水', '20', 1);

INSERT IGNORE INTO `ws_qrcode`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `QRCODE_CONTENT`, `QRCODE_TYPE`, `DEVICE_ID`, `OUTLET_ID`, `QRCODE_STATUS`) VALUES
(9401, 0, 1, '20260723000000', 1, '20260723000000', 'ACC-QR-DEV1-O1', 1, 9201, 9301, 1),
(9402, 0, 1, '20260723000000', 1, '20260723000000', 'ACC-QR-DEV2-O1', 1, 9202, 9302, 1);

-- 验收套餐：仅允许水站 9101（SCOPE_JSON 形状与 WaterCardScope.normalize 的 specified 口径一致）。
-- 100 元 / 500 升 / 365 天，与一期主推套餐同数值域，保证 RechargeLimits 校验可过。
INSERT IGNORE INTO `ws_package`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `PACKAGE_NAME`, `PAY_AMOUNT`, `WATER_ML`, `BONUS_AMOUNT`, `UNIT_PRICE_SNAP`, `SCOPE_JSON`, `EXPIRE_DAYS`, `PACKAGE_STATUS`, `PACKAGE_REMARK`) VALUES
(9501, 0, 1, '20260723000000', 1, '20260723000000', '验收100元500升卡', 10000, 500000, 0, '20.00', '{"scopeType":"specified","stationIds":["9101"]}', NULL, 1, '水卡生命周期验收专用：仅允许验收水站1；付费卡永久有效（D-213）');

-- 纯金额套餐（E2E-04 售后链前置）：配送下单的水费(payWay=2)与配送费(两种支付方式都要)都从
-- BALANCE_AMOUNT 出，而 9501 是水量套餐（BONUS_AMOUNT=0 → 到账余额恒 0），单靠它无法走通配送。
-- WATER_ML=0 + UNIT_PRICE_SNAP='0' 即 RechargeLimits 认定的纯金额套餐，权益 = PAY_AMOUNT + BONUS_AMOUNT。
INSERT IGNORE INTO `ws_package`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `PACKAGE_NAME`, `PAY_AMOUNT`, `WATER_ML`, `BONUS_AMOUNT`, `UNIT_PRICE_SNAP`, `SCOPE_JSON`, `EXPIRE_DAYS`, `PACKAGE_STATUS`, `PACKAGE_REMARK`) VALUES
(9502, 0, 1, '20260723000000', 1, '20260723000000', '验收50元余额包', 5000, 0, 0, '0', '{"scopeType":"specified","stationIds":["9101"]}', NULL, 1, '售后验收专用：纯金额套餐，为配送水费与配送费提供余额');

-- 配送员账号（E2E-03 配送链 + E2E-04 售后链共用）。
-- 曾经每轮 rebuild 后手工补一次，既易漏 --default-character-set=utf8mb4 灌出乱码，
-- 又让「验收环境完全由脚本重建」这条前提名存实亡，故固化进种子。
-- 服务范围只给 9101：范围外拒绝仍要由 9102 保持可验。
INSERT IGNORE INTO `ws_user`
(`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `USER_NAME`, `USER_GENDER`, `USER_PHONE`, `USER_IDENTITY_CIPHER`, `USER_AVATAR`, `DISABLED_FLAG`, `USER_STATUS`, `POINTS`, `WECHAT_XCX_OPENID`, `CHANNEL_USER_ID`, `REFERRER_USER_ID`, `PROMO_CODE`) VALUES
(9003, 1, '20260723000000', 1, '20260723000000', 0, '验收配送员', 1, '13999990003', 'acc-cipher-9003', NULL, 1, 1, 0, NULL, NULL, NULL, NULL);

INSERT IGNORE INTO `ws_courier`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `USER_ID`, `COURIER_NAME`, `COURIER_PHONE`, `ID_CARD_NO`, `STATION_IDS`, `SERVICE_REGION`, `COURIER_STATUS`, `AUDIT_REMARK`) VALUES
(9601, 0, 1, '20260723000000', 1, '20260723000000', 9003, '验收配送员', '13999990003', NULL, '9101', '验收专区', 2, '水卡生命周期验收专用：仅服务验收水站1');

-- ============================================================
-- E2E-07 消息中心种子（包A）
-- 消息主样本由验收 runner 走真实业务链产生（配送四节点/申诉/工单钩子），种子只补三类
-- runner 造不出来的既有事实：跨域历史消息（验证域筛选与排序）、他人消息（S8 越权样本）、
-- 一条外部渠道发送失败的历史消息（S9 重试/降级骨架的唯一入口——真实链路一期
-- 只产站内已送达消息，失败态只能以「历史遗留事实」形式存在于隔离环境，绝不进主库）。
-- MSG_CHANNEL=2 表示微信订阅渠道占位（前端契约 wechat-subscribe；字典未登记该值，
-- 属骨架预留，管理端记录页按原值展示）。
-- ============================================================
INSERT IGNORE INTO `ws_message`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `USER_ID`, `MSG_DOMAIN`, `MSG_TITLE`, `MSG_CONTENT`, `MSG_CHANNEL`, `SEND_STATUS`, `SEND_TIME`, `READ_FLAG`, `OBJECT_TYPE`, `OBJECT_ID`) VALUES
(9701, 0, 1, '20260601090000', 1, '20260601090000', 9001, 5, '系统公告：验收环境说明', '本环境为隔离验收环境，数据每轮重建。', 1, 4, '20260601090000', 1, NULL, NULL),
(9702, 0, 1, '20260610100000', 1, '20260610100000', 9001, 1, '取水完成', '您在验收水站1的取水订单已完成。', 1, 4, '20260610100000', 0, 'order', 'ACC-HIST-WATER-1'),
(9703, 0, 1, '20260615110000', 1, '20260615110000', 9002, 5, '系统公告：验收环境说明', '本环境为隔离验收环境，数据每轮重建。', 1, 4, '20260615110000', 0, NULL, NULL),
(9704, 0, 1, '20260620120000', 1, '20260620120000', 9001, 2, '水卡到期提醒（历史样本）', '您的水卡权益即将到期，请留意使用期限。', 2, 3, '20260620120000', 0, NULL, NULL);

-- ============================================================
-- E2E-08 分账比例演示配置（包A）：万分比，演示值待甲方确认后调整（任务书口径3）。
-- 生效时间取业务起点之前，保证验收窗口内全部订单命中本版本；
-- 比例变更场景（S4）由 runner 插新生效版本验证，不在种子里预置。
-- ============================================================
INSERT IGNORE INTO `ws_split_config`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `PRODUCT_LINE`, `RECEIVER_TYPE`, `SPLIT_RATE`, `EFFECT_TIME`, `CONFIG_REMARK`) VALUES
(9801, 0, 1, '20260101000000', 1, '20260101000000', 1, 1, 7000, '20260101000000', '售水-机主 70%（演示值）'),
(9802, 0, 1, '20260101000000', 1, '20260101000000', 1, 3, 3000, '20260101000000', '售水-平台 30%（演示值）'),
(9803, 0, 1, '20260101000000', 1, '20260101000000', 2, 1, 6000, '20260101000000', '配送-机主 60%（演示值）'),
(9804, 0, 1, '20260101000000', 1, '20260101000000', 2, 2, 3000, '20260101000000', '配送-配送员 30%（演示值）'),
(9805, 0, 1, '20260101000000', 1, '20260101000000', 2, 3, 1000, '20260101000000', '配送-平台 10%（演示值）');

-- ============================================================
-- E2E-09 商城验收种子（S1~S16 全链所需的最小真实底座）。
--
-- ID 段 99xx，与既有 9x xx 段互不重叠：
--   9901       ws_mall_category           验收品类
--   9911/9912  ws_mall_product            桶装水 / 净水滤芯
--   9921~9923  ws_mall_sku                两款商品共三个 SKU
--   9931/9932  ws_mall_warehouse          验收前置仓A（独占服务 420117）/ 前置仓B（420118，越权对照）
--   9941~9944  ws_mall_stock              仓A 三个 SKU + 仓B 一个 SKU
--   9951/9952  ws_mall_courier_scope      配送员 9601 只覆盖仓A
--   9961/9962  ws_mall_warehouse_operator 运营 9001 只管仓A（acc-ops2=9002 一个仓都不管，作越权对照）
--   9971       ws_user_address            用户 9001 的收货地址，区县码 420117 → 唯一命中仓A
--
-- 刻意让 acc-ops2(9002) 不绑定任何前置仓：S15 的越权拒绝必须由归属判据给出，
-- 而不是由「这个账号没登录成功」之类的旁因给出。
--
-- 库存给足（各 200 件）：验收要反复跑，库存不足导致的失败会把真正的缺陷淹掉。
-- ============================================================
INSERT IGNORE INTO `ws_mall_category`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`CATEGORY_CODE`,`CATEGORY_NAME`,`CATEGORY_SORT`,`CATEGORY_STATUS`) VALUES
(9901, 0, 1, '20260801000000', 1, '20260801000000', 'ACC-MALL-CAT-1', '验收饮水用品', 1, 1);

INSERT IGNORE INTO `ws_mall_product`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`PRODUCT_NO`,`CATEGORY_ID`,`PRODUCT_NAME`,`PRODUCT_SUBTITLE`,`COVER_URL`,`PRODUCT_DESC`,`PRODUCT_STATUS`,`VERSION`) VALUES
(9911, 0, 1, '20260801000000', 1, '20260801000000', 'ACC-MALL-P-0001', 9901, '验收桶装水', '18.9L 家庭装', NULL, '商城验收专用商品', 2, 1),
(9912, 0, 1, '20260801000000', 1, '20260801000000', 'ACC-MALL-P-0002', 9901, '验收净水滤芯', 'PP 棉滤芯', NULL, '商城验收专用商品', 2, 1);

INSERT IGNORE INTO `ws_mall_sku`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`SKU_NO`,`PRODUCT_ID`,`SKU_NAME`,`SPEC_SNAP`,`SALE_PRICE`,`MARKET_PRICE`,`WEIGHT_GRAM`,`SKU_STATUS`,`VERSION`) VALUES
(9921, 0, 1, '20260801000000', 1, '20260801000000', 'ACC-MALL-S-0001', 9911, '单桶', '{"规格":"18.9L"}', 1800, 2200, 19500, 1, 1),
(9922, 0, 1, '20260801000000', 1, '20260801000000', 'ACC-MALL-S-0002', 9911, '两桶装', '{"规格":"18.9L×2"}', 3400, 4400, 39000, 1, 1),
(9923, 0, 1, '20260801000000', 1, '20260801000000', 'ACC-MALL-S-0003', 9912, '标准滤芯', '{"型号":"PP-10"}', 900, 1200, 300, 1, 1);

-- 服务范围形状与 MallWarehouseServiceImpl.buildScopeJson 一致（districts + 六位区县码）
INSERT IGNORE INTO `ws_mall_warehouse`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`WAREHOUSE_NO`,`WAREHOUSE_NAME`,`CONTACT_NAME`,`CONTACT_PHONE`,`PROVINCE_CODE`,`CITY_CODE`,`DISTRICT_CODE`,`WAREHOUSE_ADDRESS`,`LONGITUDE`,`LATITUDE`,`SERVICE_SCOPE_JSON`,`WAREHOUSE_STATUS`,`VERSION`) VALUES
(9931, 0, 1, '20260801000000', 1, '20260801000000', 'ACC-WH-001', '验收前置仓A', '仓管甲', '13999990011', '420000', '420100', '420117', '验收专区前置仓A', NULL, NULL, '{"scopeType":"districts","districtCodes":["420117"]}', 1, 1),
(9932, 0, 1, '20260801000000', 1, '20260801000000', 'ACC-WH-002', '验收前置仓B', '仓管乙', '13999990012', '420000', '420100', '420118', '验收专区前置仓B', NULL, NULL, '{"scopeType":"districts","districtCodes":["420118"]}', 1, 1);

INSERT IGNORE INTO `ws_mall_stock`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`WAREHOUSE_ID`,`SKU_ID`,`AVAILABLE_QTY`,`RESERVED_QTY`,`VERSION`) VALUES
(9941, 0, 1, '20260801000000', 1, '20260801000000', 9931, 9921, 200, 0, 1),
(9942, 0, 1, '20260801000000', 1, '20260801000000', 9931, 9922, 200, 0, 1),
(9943, 0, 1, '20260801000000', 1, '20260801000000', 9931, 9923, 200, 0, 1),
(9944, 0, 1, '20260801000000', 1, '20260801000000', 9932, 9921, 200, 0, 1);

-- 配送归属：9601 只覆盖仓A。仓B 无任何配送员，S15 越权对照据此成立
INSERT IGNORE INTO `ws_mall_courier_scope`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`WAREHOUSE_ID`,`COURIER_ID`) VALUES
(9951, 0, 1, '20260801000000', 1, '20260801000000', 9931, 9601);

-- 前置仓操作员：运营 9001 只管仓A；9002 刻意不给任何绑定
INSERT IGNORE INTO `ws_mall_warehouse_operator`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`WAREHOUSE_ID`,`OPERATOR_ID`) VALUES
(9961, 0, 1, '20260801000000', 1, '20260801000000', 9931, 9001);

-- 收货地址：区县码 420117 唯一命中仓A。刻意避开演示仓也服务的 420111——
-- 选仓规则是「候选中按仓库ID升序取第一个」，与演示仓共用区县码会让验收单落到 ID 更小的演示仓，
-- 而操作员/配送员只绑定了验收仓，整条链会因为一个种子巧合而全红。
INSERT IGNORE INTO `ws_user_address`
(`ID`,`DATA_STATUS`,`CREATE_BY`,`CREATE_TIME`,`UPDATE_BY`,`UPDATE_TIME`,`USER_ID`,`CONTACT_NAME`,`CONTACT_PHONE`,`REGION`,`DISTRICT_CODE`,`ADDRESS_DETAIL`,`IS_DEFAULT`,`LOCATION_AUTHORIZED`) VALUES
(9971, 0, 1, '20260801000000', 1, '20260801000000', 9001, '验收卡主', '13999990001', '湖北省武汉市新洲区', '420117', '验收专区商城收货点1号', 1, 0);

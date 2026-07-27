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
-- LOGIN_PWD 与 01-base 的 admin 为同一 RSA 密文（登录时两侧都走 RSAUtils.decrypt 后比较明文），
-- 不引入新明文口令，也不改动底座 admin。
-- 该密文是 **Demo 固定密文**，仅与源码内置的 Demo RSA 密钥对（示例值见 .env.example 的
-- DAKANG_RSA_PRIVATE_KEY/DAKANG_RSA_PUBLIC_KEY 注释）配套，公开即等同明文，只可用于验收环境。
-- 生产部署必须更换 RSA 密钥对并重置管理员口令；届时 e2e 的 ACC_OPS_PWD_CIPHER 也要同步换成新密文。
INSERT IGNORE INTO `api_employee`
(`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `LOGIN_NAME`, `LOGIN_PWD`, `EMPLOYEE_NAME`, `EMPLOYEE_GENDER`, `EMPLOYEE_PHONE`, `DEPT_ID`, `POSITION_ID`, `DISABLED_FLAG`) VALUES
(9001, 1, '20260723000000', 1, '20260723000000', 0, 'acc-ops', 'B51yw4neAThtpTnUhsmvp+Ikho2Cu6ToTXw3c3iDtbTR7HSOfPIk6vY0cHjGRQzy6UdINaYqQbKpKeBOYSYAw/d1oQM2OmevVJ2UgCUTi3eVopeNXL5YHk+Uyx6JDV61M0M3kymmNn5ZLPaaqZeYMH0YHRKQMdDHXDpNl05IkhQ=', '验收运营', 1, '13999990009', 1, 1, 1);

INSERT IGNORE INTO `api_rbac_role_employee`
(`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `ROLE_ID`, `EMPLOYEE_ID`) VALUES
(9001, 1, '20260723000000', 1, '20260723000000', 0, 1, 9001);

-- C 端账号：9001 无卡卡主（S1 起点），9002 成员（S9 授权对象）
INSERT IGNORE INTO `ws_user`
(`ID`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DATA_STATUS`, `USER_NAME`, `USER_GENDER`, `USER_PHONE`, `USER_IDENTITY_CIPHER`, `USER_AVATAR`, `DISABLED_FLAG`, `USER_STATUS`, `POINTS`, `WECHAT_XCX_OPENID`, `CHANNEL_USER_ID`, `REFERRER_USER_ID`, `PROMO_CODE`) VALUES
(9001, 1, '20260723000000', 1, '20260723000000', 0, '验收卡主', 1, '13999990001', 'acc-cipher-9001', NULL, 1, 1, 0, NULL, NULL, NULL, NULL),
(9002, 1, '20260723000000', 1, '20260723000000', 0, '验收成员', 2, '13999990002', 'acc-cipher-9002', NULL, 1, 1, 0, NULL, NULL, NULL, NULL);

-- 两个验收水站：9101 在套餐范围内，9102 用于 S6 范围外拒绝
INSERT IGNORE INTO `ws_station`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `STATION_NAME`, `STATION_CODE`, `STATION_REGION`, `STATION_ADDRESS`, `STATION_LNG`, `STATION_LAT`, `STATION_STATUS`, `OWNER_USER_ID`, `CHANNEL_USER_ID`, `STATION_REMARK`) VALUES
(9101, 0, 1, '20260723000000', 1, '20260723000000', '验收水站1', 'WS-ACC-001', '验收专区', '验收环境水站1（套餐范围内）', NULL, NULL, 1, NULL, NULL, '水卡生命周期验收专用'),
(9102, 0, 1, '20260723000000', 1, '20260723000000', '验收水站2', 'WS-ACC-002', '验收专区', '验收环境水站2（范围外拒绝用）', NULL, NULL, 1, NULL, NULL, '水卡生命周期验收专用');

-- 两台设备均在线空闲：S6 的拒绝必须由「卡范围」给出，而不是设备离线/故障抢先拒绝
INSERT IGNORE INTO `ws_device`
(`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `DEVICE_NO`, `DEVICE_NAME`, `DEVICE_MODEL`, `STATION_ID`, `OWNER_USER_ID`, `FIRMWARE_VERSION`, `SIM_ICCID`, `SIM_CARRIER`, `ONLINE_STATUS`, `RUN_STATUS`, `LAST_HEARTBEAT`, `LAST_FAULT_CODE`, `SIGNAL_STRENGTH`, `DEVICE_REMARK`) VALUES
(9201, 0, 1, '20260723000000', 1, '20260723000000', 'ACC-DEV-0001', '验收1号机', 'DK-W800', 9101, NULL, 'v1.0.0', NULL, NULL, 1, 1, '20260723000000', NULL, -60, '水卡生命周期验收专用（tools/device-sim 扮演）'),
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

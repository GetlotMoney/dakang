-- ============================================================
-- L1a 验收种子补齐（S1 切片，task_mrrx4jd2）
-- 目的：补齐扫码链拒绝/离线分支的真链验收样本，不改表、不改字典。
-- 幂等：INSERT IGNORE + 同值条件 UPDATE，可重复执行。
-- 镜像：本文件与 deploy/mysql/init/02-ws-business.sql 的 ws_qrcode 种子块保持一致；
--       全新环境由 02 初始化，存量环境执行本文件补齐。
-- 对齐：H2 用例库 v1.2（ctx_mrrv9i3y）——B2 禁用码 DK-QR-DISABLED-001、
--       C1 设备离线用 DK-QR-DEV0002-O1（resolve 成功、eligibility 返 DEVICE_OFFLINE）。
-- ============================================================

-- ① 设备 2 扫码入口（02-ws-business.sql 已含此行；存量库缺失时由本文件补齐）
--    绑定 DK-DEV-0002（离线+故障 E003）/ 出水口 ID=3，供 DEVICE_OFFLINE/FAULT 预检真链。
INSERT IGNORE INTO `ws_qrcode` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `QRCODE_CONTENT`, `QRCODE_TYPE`, `DEVICE_ID`, `OUTLET_ID`, `QRCODE_STATUS`) VALUES
(4, 0, 1, '20260710120000', 1, '20260710120000', 'DK-QR-DEV0002-O1', 1, 2, 3, 1);

-- ② 常驻禁用码（QRCODE_STATUS=2）：模拟“曾绑定设备 1 出水口 1、后被停用”的码，
--    供 QR_EXPIRED(5402) 真链验收；免去 H2-B2 用例临时 UPDATE ID=4 再回写的操作。
INSERT IGNORE INTO `ws_qrcode` (`ID`, `DATA_STATUS`, `CREATE_BY`, `CREATE_TIME`, `UPDATE_BY`, `UPDATE_TIME`, `QRCODE_CONTENT`, `QRCODE_TYPE`, `DEVICE_ID`, `OUTLET_ID`, `QRCODE_STATUS`) VALUES
(5, 0, 1, '20260710120000', 1, '20260710120000', 'DK-QR-DISABLED-001', 1, 1, 1, 2);

-- ③ 确保离线/故障设备样本：DK-DEV-0002 固定为 离线(2)+故障(3)+E003（与 02 种子同值，
--    幂等恢复运行期漂移，例如 dev 实例心跳扫描误刷）。不触碰 DK-DEV-0001 在线基线。
UPDATE `ws_device`
   SET `ONLINE_STATUS` = 2,
       `RUN_STATUS` = 3,
       `LAST_FAULT_CODE` = 'E003'
 WHERE `ID` = 2
   AND `DEVICE_NO` = 'DK-DEV-0002'
   AND (`ONLINE_STATUS` <> 2 OR `RUN_STATUS` <> 3 OR `LAST_FAULT_CODE` <> 'E003' OR `LAST_FAULT_CODE` IS NULL);

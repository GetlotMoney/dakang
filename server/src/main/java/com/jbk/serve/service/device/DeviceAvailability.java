package com.jbk.serve.service.device;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.device.po.WsFaultDict;
import com.jbk.tool.utils.DateUtils;

import java.util.Set;

/**
 * 设备运营可用性判定——单一出处（E2E-05 包B，任务书 5.1）：扫码预检、取水创单、PC 设备详情、
 * 告警引擎、远程指令资格、机主设备详情六个入口共用。阻断顺序即优先级：离线（含未激活）→
 * 故障态（未知故障码 fail-closed 一律阻断，任务书 3.6；已登记码按 BLOCK_ORDER_FLAG）→
 * 维护中/锁定/出水中 → 出水口停用（仅在给出出水口时判定）。本类只判定不查库
 * （字典行由调用方传入，判定可纯单测穷举）；共键错位不在射程，属
 * {@code TradeOrderTxServiceImpl.verifyArchiveCoKeys} 的事务内职责。
 *
 * @author dakang
 * @since 2026-07-30
 */
public final class DeviceAvailability {

    /** 判定结论。{@code blocked()} 为 true 的一律阻断取水与出水类指令。 */
    public record Verdict(String code, String reason) {
        public boolean available() {
            return "AVAILABLE".equals(code);
        }
    }

    /**
     * 有专属可用性码的故障（前端按码展示定制文案）；其余阻断性故障统一 DEVICE_UNAVAILABLE。
     * 与 E2E-01 冻结的预检契约一致，改动会破坏小程序既有文案分支。
     */
    private static final Set<String> FAULT_AVAILABILITY_CODES = Set.of("E001", "E003", "E004");

    private DeviceAvailability() {
    }

    /**
     * 判定设备（含可选出水口）的运营可用性。
     *
     * @param device 设备行（调用方保证非空、未逻辑删除）
     * @param fault  当前故障码在字典中的行；设备无故障码或未登记时传 null
     * @param outlet 目标出水口；仅看设备整体状态时传 null（机主详情/告警引擎）
     */
    public static Verdict judge(WsDevice device, WsFaultDict fault, WsDeviceOutlet outlet) {
        if (ObjectUtil.isNull(device)) {
            return new Verdict("DEVICE_UNAVAILABLE", "设备档案缺失");
        }
        if (!ObjectUtil.equal(device.getOnlineStatus(), DeviceEnum.OnlineStatus.ONLINE.getValue())) {
            return new Verdict("DEVICE_OFFLINE", "设备离线，请稍后再试");
        }
        String simBlockReason = simBlockReason(device, DateUtils.time());
        if (StrUtil.isNotBlank(simBlockReason)) {
            return new Verdict("DEVICE_UNAVAILABLE", simBlockReason);
        }
        Integer run = device.getRunStatus();
        if (ObjectUtil.equal(run, DeviceEnum.RunStatus.FAULT.getValue())) {
            String faultCode = device.getLastFaultCode();
            if (ObjectUtil.isNull(fault)) {
                // 未知故障 fail-closed：没有字典行就没有「不阻断」的依据
                return new Verdict("DEVICE_UNAVAILABLE",
                        "设备故障（未知故障码" + StrUtil.blankToDefault(faultCode, "未知") + "），安全起见暂不可用");
            }
            if (ObjectUtil.equal(fault.getBlockOrderFlag(), ApiEnum.Flag.YES.value())) {
                if (FAULT_AVAILABILITY_CODES.contains(faultCode)) {
                    return new Verdict("FAULT_" + faultCode, fault.getFaultName());
                }
                return new Verdict("DEVICE_UNAVAILABLE",
                        "设备故障（" + faultCode + "）" + fault.getFaultName());
            }
            // 命中字典且不阻断：继续判出水口
        } else if (ObjectUtil.equal(run, DeviceEnum.RunStatus.MAINTAIN.getValue())) {
            return new Verdict("DEVICE_UNAVAILABLE", "设备维护中");
        } else if (ObjectUtil.equal(run, DeviceEnum.RunStatus.LOCKED.getValue())) {
            return new Verdict("DEVICE_UNAVAILABLE", "设备已锁定");
        } else if (ObjectUtil.equal(run, DeviceEnum.RunStatus.DISPENSING.getValue())) {
            return new Verdict("DEVICE_UNAVAILABLE", "设备正在出水中，请稍后再试");
        } else if (!ObjectUtil.equal(run, DeviceEnum.RunStatus.IDLE.getValue())) {
            // 未登记运行状态 fail-closed：新增状态必须显式进入本判定后才可放行
            return new Verdict("DEVICE_UNAVAILABLE", "设备运行状态未知，安全起见暂不可用");
        }
        if (ObjectUtil.isNotNull(outlet)
                && ObjectUtil.notEqual(outlet.getOutletStatus(), 1)) {
            return new Verdict("NO_AVAILABLE_OUTLET", "当前出水口已停用");
        }
        return new Verdict("AVAILABLE", null);
    }

    /**
     * SIM 档案阻断口径。状态为空兼容尚未补齐 SIM 档案的存量设备；一旦明确为未激活、欠费、停用，
     * 或到期时间已过/格式非法，就不得继续依赖“设备仍在线”放行取水。
     */
    public static String simBlockReason(WsDevice device, String now) {
        Integer status = device.getSimStatus();
        if (ObjectUtil.isNotNull(status)) {
            DeviceEnum.SimStatus simStatus;
            try {
                simStatus = DeviceEnum.SimStatus.getType(status);
            } catch (RuntimeException e) {
                return "SIM状态异常，请联系运维核对";
            }
            if (simStatus != DeviceEnum.SimStatus.NORMAL) {
                return "SIM卡" + simStatus.getDesc() + "，设备暂不可用";
            }
        }
        String expireTime = device.getSimExpireTime();
        if (StrUtil.isBlank(expireTime)) {
            return null;
        }
        if (expireTime.length() != 14 || !StrUtil.isNumeric(expireTime)) {
            return "SIM到期时间异常，请联系运维核对";
        }
        if (expireTime.compareTo(now) <= 0) {
            return "SIM卡已到期，设备暂不可用";
        }
        return null;
    }
}

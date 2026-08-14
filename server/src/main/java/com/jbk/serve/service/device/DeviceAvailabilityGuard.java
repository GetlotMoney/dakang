package com.jbk.serve.service.device;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsFaultDictMapper;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.device.po.WsFaultDict;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 设备可用性的唯一加载器：设备、出水口、故障字典按同一时刻读齐交 {@link DeviceAvailability}
 * 判定，数据与结论作为不可变上下文一并交还——调用方另读一份会形成「判定用 A 版本、
 * 落库用 B 版本」的混合判定。
 *
 * <p>{@link #loadForUpdate} 供扣卡事务内：REPEATABLE READ 快照可能过期，设备/出水口取排他
 * 当前读、故障字典取共享当前读；{@link #loadCurrent} 供非事务场景。锁顺序全仓唯一（反向即死锁）：
 * 设备(X) → 出水口(X) → 故障字典(S)，调用方随后 二维码(S) → 水站(S) → 水卡(X) → 成员关系(X)，
 * 不得在调用本类之前先锁水卡。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Component
@RequiredArgsConstructor
public class DeviceAvailabilityGuard {

    private final WsDeviceMapper deviceMapper;
    private final WsDeviceOutletMapper outletMapper;
    private final WsFaultDictMapper faultDictMapper;

    /**
     * 一次判定所依据的全部权威数据与结论（不可变）。调用方的共键核对、报文装配与下发目标
     * <b>只能</b>取自本记录，不得另行读取第二份档案。
     *
     * @param device  当前读到的设备行；档案缺失或已删除为 null
     * @param outlet  当前读到的出水口行；档案缺失或已删除为 null
     * @param fault   设备当前故障码在字典中的行；无故障码或未登记为 null
     * @param verdict 基于以上三者得出的可用性结论
     */
    public record CheckedDeviceContext(WsDevice device, WsDeviceOutlet outlet, WsFaultDict fault,
                                       DeviceAvailability.Verdict verdict) {

        public boolean available() {
            return verdict.available();
        }

        /** 档案缺失：设备或出水口任一读不到，共键与可用性都无从谈起。 */
        public boolean archiveMissing() {
            return ObjectUtil.isNull(device) || ObjectUtil.isNull(outlet);
        }

        /** 供拒绝文案与审计使用的原因（可用时为 null）。 */
        public String reason() {
            return verdict.reason();
        }
    }

    /** 事务内权威当前读：设备与出水口取行锁、故障字典取共享锁，顺序即上文锁序。 */
    public CheckedDeviceContext loadForUpdate(Long deviceId, Long outletId) {
        WsDevice device = ObjectUtil.isNull(deviceId) ? null : deviceMapper.selectByIdForUpdate(deviceId);
        WsDeviceOutlet outlet = ObjectUtil.isNull(outletId) ? null : outletMapper.selectByIdForUpdate(outletId);
        return build(device, outlet, loadFaultsForShare(device));
    }

    /** 非事务当前状态读（仅供不在事务内的只读场景；出水指令准备已改走事务内 loadForUpdate）。 */
    public CheckedDeviceContext loadCurrent(Long deviceId, Long outletId) {
        WsDevice device = ObjectUtil.isNull(deviceId) ? null : deviceMapper.selectById(deviceId);
        WsDeviceOutlet outlet = ObjectUtil.isNull(outletId) ? null : outletMapper.selectById(outletId);
        return build(device, outlet, loadFaultsForShare(device));
    }

    /**
     * 已持有档案对象时的判定入口（扫码预检、PC 展示等只读面）：只补查故障字典，不重复读档案。
     */
    public CheckedDeviceContext judge(WsDevice device, WsDeviceOutlet outlet) {
        return build(device, outlet, loadFaultsForShare(device));
    }

    /**
     * 调用方已批量读好字典时的判定入口（PC 列表页等，避免 N+1）。
     * 调用方须把该故障码的全部有效行原样交入，不得预先去重或排序——那会压平重复配置。
     */
    public CheckedDeviceContext judgeWithFaults(WsDevice device, WsDeviceOutlet outlet,
                                                List<WsFaultDict> faults) {
        List<WsFaultDict> rows = faults == null ? List.of() : faults;
        if (ObjectUtil.isNotNull(device) && ObjectUtil.isNull(outlet)) {
            // 此处 null 是「这台设备一个出水口都没有」而非「指定口读不到」：
            // 设备侧有问题先报设备级原因，设备侧无问题才落 NO_AVAILABLE_OUTLET
            CheckedDeviceContext deviceOnly = build(device, null, rows);
            if (!ObjectUtil.equal(deviceOnly.verdict().code(), "NO_AVAILABLE_OUTLET")) {
                return deviceOnly;
            }
            DeviceAvailability.Verdict withoutOutlet = DeviceAvailability.judge(
                    device, rows.size() == 1 ? rows.get(0) : null, null);
            return withoutOutlet.available()
                    ? new CheckedDeviceContext(device, null, null,
                            new DeviceAvailability.Verdict("NO_AVAILABLE_OUTLET", "当前设备没有可用出水口"))
                    : new CheckedDeviceContext(device, null, null, withoutOutlet);
        }
        return build(device, outlet, rows);
    }

    /**
     * 出水口缺失按停用处理：给定了 outletId 却读不到行，等同于这个口不可用，不得退化成
     * 「只判设备」——那会把「口已删除」放行成可取水。
     */
    private CheckedDeviceContext build(WsDevice device, WsDeviceOutlet outlet, List<WsFaultDict> faults) {
        if (ObjectUtil.isNull(device)) {
            return new CheckedDeviceContext(null, outlet, null,
                    new DeviceAvailability.Verdict("DEVICE_UNAVAILABLE", "设备档案缺失或已删除"));
        }
        if (ObjectUtil.isNull(outlet)) {
            return new CheckedDeviceContext(device, null, null,
                    new DeviceAvailability.Verdict("NO_AVAILABLE_OUTLET", "出水口档案缺失或已删除"));
        }
        // 同一故障码多条有效配置（FAULT_CODE 只是普通索引挡不住重复录入）：
        // 任选或合并都不可靠，一律 fail-closed，冲突写进拒因交人工收敛
        if (faults.size() > 1) {
            return new CheckedDeviceContext(device, outlet, null,
                    new DeviceAvailability.Verdict("FAULT_DICT_CONFLICT",
                            "故障码 " + device.getLastFaultCode() + " 在字典中存在 " + faults.size()
                                    + " 条有效配置，配置冲突，安全起见暂不可用"));
        }
        WsFaultDict fault = faults.isEmpty() ? null : faults.get(0);
        return new CheckedDeviceContext(device, outlet, fault,
                DeviceAvailability.judge(device, fault, outlet));
    }

    /**
     * 未登记故障码返回空列表，由 {@link DeviceAvailability} 在故障态下 fail-closed 阻断
     * （厂商新码在字典跟上前绝不放行取水）。设备无故障码时不查库。
     */
    private List<WsFaultDict> loadFaultsForShare(WsDevice device) {
        if (ObjectUtil.isNull(device) || StrUtil.isBlank(device.getLastFaultCode())) {
            return List.of();
        }
        return faultDictMapper.selectByCodeForShare(device.getLastFaultCode());
    }
}

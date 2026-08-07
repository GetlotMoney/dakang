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
 * 设备可用性的<b>唯一加载器</b>：把设备、出水口、故障字典按同一时刻读齐，交
 * {@link DeviceAvailability} 判定，并把这批数据与结论一起作为不可变上下文交还调用方。
 *
 * <h3>为什么必须返回上下文而不是光返回结论</h3>
 * <p>调用方拿到「可用」之后还要用设备与出水口做共键核对、装配指令报文、决定 publish 到哪个
 * deviceNo。如果调用方另外再读一份，就会出现「判定用 A 版本、落库用 B 版本」的混合判定——
 * 中间任何一次改绑或迁站都会从缝里漏过去。所以判定与被判定的数据必须是同一批，且只此一批。</p>
 *
 * <h3>两个提交点，两种读法</h3>
 * <ol>
 *   <li>{@link #loadForUpdate} —— 扣卡事务内。REPEATABLE READ 下普通 SELECT 读的是事务首次
 *       一致性读建立的快照；下单事务在锁卡上可能等待较久，期间的档案变更对快照不可见，
 *       因此设备与出水口取排他当前读、故障字典取共享当前读。</li>
 *   <li>{@link #loadCurrent} —— 出水指令创建之前。扣款事务已提交，此处不在事务内，
 *       每条语句自成读视图，普通读即当前值。</li>
 * </ol>
 *
 * <h3>锁顺序（全仓唯一，反向即死锁）</h3>
 * <p>设备(X) → 出水口(X) → 故障字典(S)，随后由调用方继续 二维码(S) → 水站(S) → 水卡(X) →
 * 成员关系(X)。本类内部严格按此顺序取锁，调用方不得在调用本类之前先锁水卡。</p>
 *
 * <p>两个提交点都读当前值，不代表消除竞态：设备状态在报文传输途中变化是物理事实，
 * 平台侧只能保证「服务端做出决定的那一刻读到的是最新已提交值」。</p>
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
     * 已持有档案对象时的判定入口（扫码预检、PC 展示等只读面）：只补查故障字典，
     * 不重复读档案。与两个当前读入口共用同一套 0/1/多行处理，重复配置在哪条路径上都阻断。
     */
    public CheckedDeviceContext judge(WsDevice device, WsDeviceOutlet outlet) {
        return build(device, outlet, loadFaultsForShare(device));
    }

    /**
     * 调用方已批量读好字典时的判定入口（PC 列表页等，避免逐设备查库形成 N+1）。
     * 0/1/多行的处理仍在本类，调用方只负责把「该设备故障码对应的全部有效行」原样交进来——
     * 一旦在调用方做去重或排序，重复配置就在那里被压平了，判定再严也看不见。
     */
    public CheckedDeviceContext judgeWithFaults(WsDevice device, WsDeviceOutlet outlet,
                                                List<WsFaultDict> faults) {
        List<WsFaultDict> rows = faults == null ? List.of() : faults;
        if (ObjectUtil.isNotNull(device) && ObjectUtil.isNull(outlet)) {
            // 与按 ID 取档案的两个入口语义不同，此处的 null 是「这台设备一个出水口都没有」而不是
            // 「指定的那个口读不到」：设备本身离线/故障时应当先报设备级原因，报「出水口缺失」会
            // 把运维引到错误的方向。设备侧无问题时才落 NO_AVAILABLE_OUTLET。
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
        // 同一故障码多条有效配置：库层 FAULT_CODE 只是普通索引，挡不住重复录入。
        // 此时「是否阻断下单」没有唯一答案——任选一条等于让运维在两条相反配置之间掷骰子，
        // 合并成不阻断更是把安全默认值调松。一律 fail-closed，并把冲突写进拒因交人工收敛。
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

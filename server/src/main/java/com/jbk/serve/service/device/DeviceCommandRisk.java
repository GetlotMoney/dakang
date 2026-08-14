package com.jbk.serve.service.device;

import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.exception.JbkException;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 设备指令风险判据（D-423）——唯一实现：判据有第二份实现，安全强度就等于最松的那一份。
 * 档位：恒加闸（{@link DeviceEnum.CmdTier#GATED}）=紧急停机、价格同步（REQ-063 地板，
 * 不因范围降档）；单台可逆（{@link DeviceEnum.CmdTier#REVERSIBLE_SINGLE}）=锁机/解锁/
 * 参数同步/重启，范围升到水站或全部时加闸；只读恒不加闸不升档（扇出负载用目标数上限治）。
 * 「单台可逆」的证据来自模拟器：若真机锁机需现场解，LOCK 应升 GATED——重开条件登记在 D-423。
 *
 * @author dakang
 * @since 2026-08-11
 */
public final class DeviceCommandRisk {

    /** 范围：2 水站、3 全部——影响半径超出单台，可逆档在此升闸。 */
    private static final Set<Integer> WIDE_SCOPES = Set.of(2, 3);

    private DeviceCommandRisk() {
    }

    /**
     * 该指令在该范围下是否需要二次验证。
     *
     * @param cmdType   指令类型（字典 1320）
     * @param scopeType 下发范围：1 指定设备 2 水站 3 全部；单发路径传 1
     */
    public static boolean requireSafe(int cmdType, Integer scopeType) {
        DeviceEnum.CmdType type = DeviceEnum.CmdType.getType(cmdType);
        switch (type.getTier()) {
            case GATED:
                return true;
            case REVERSIBLE_SINGLE:
                return scopeType != null && WIDE_SCOPES.contains(scopeType);
            case READ_ONLY:
                return false;
            case ORDER_ONLY:
            default:
                // 出水类永远不该走到人工下发路径；走到了说明上游白名单破了，fail-closed
                throw new JbkException("出水类指令必须由订单链路触发，人工入口不受理");
        }
    }

    /**
     * 后台单发入口受理的指令类型（由档位派生，不是手写集合）。
     *
     * <p>今日求值恰为 {3,4,5,6,7}，与改造前逐位相同；差别在于恒加闸档（2/8）
     * 从此在结构上进不来，而不是靠有人记得别往集合里加。</p>
     */
    public static Set<Integer> consoleAllowedTypes() {
        return Arrays.stream(DeviceEnum.CmdType.values())
                .filter(t -> t.getTier() == DeviceEnum.CmdTier.READ_ONLY
                        || t.getTier() == DeviceEnum.CmdTier.REVERSIBLE_SINGLE)
                .map(DeviceEnum.CmdType::getValue)
                .collect(Collectors.toUnmodifiableSet());
    }
}

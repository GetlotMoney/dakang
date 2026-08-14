package com.jbk.serve.service.device;

import com.jbk.tool.consts.device.DeviceEnum;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设备指令风险档位判据单测（D-423）。
 *
 * <p>这张表是二次验证的全部判据，它一旦悄悄漂移，安全边界就跟着漂移而没有任何人知道。
 * 本类的职责是把档位表写成红灯：厂家日后答复「真机锁机需现场上电才能解」时，
 * 把 LOCK 从可逆档移到恒加闸档会立刻在这里显形，而不是靠谁记得去改。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
class DeviceCommandRiskTest {

    private static final int SCOPE_DEVICES = 1;
    private static final int SCOPE_STATION = 2;
    private static final int SCOPE_ALL = 3;

    @Test
    @DisplayName("恒加闸档：紧急停机与价格同步在任何范围都要二次验证")
    void gatedTypesRequireSafeInEveryScope() {
        for (int scope : new int[] { SCOPE_DEVICES, SCOPE_STATION, SCOPE_ALL }) {
            assertTrue(DeviceCommandRisk.requireSafe(
                    DeviceEnum.CmdType.STOP_DISPENSE.getValue(), scope),
                    "紧急停机在 scope=" + scope + " 必须加闸");
            assertTrue(DeviceCommandRisk.requireSafe(
                    DeviceEnum.CmdType.PRICE_SYNC.getValue(), scope),
                    "价格同步在 scope=" + scope + " 必须加闸");
        }
        // 这两条是 REQ-063 逐字点名的，是判据的地板：任何「按范围放宽」的改动都必须先改需求
    }

    @Test
    @DisplayName("只读档：查询状态恒不加闸，且不因范围升档")
    void readOnlyNeverRequiresSafe() {
        for (int scope : new int[] { SCOPE_DEVICES, SCOPE_STATION, SCOPE_ALL }) {
            assertFalse(DeviceCommandRisk.requireSafe(
                    DeviceEnum.CmdType.QUERY_STATUS.getValue(), scope),
                    "查询状态在 scope=" + scope + " 不该加闸");
        }
        // 只读的范围风险是 MQTT 扇出负载，用目标数上限治；用二级认证治它只会让运营
        // 每次看一眼设备状态都要输口令，然后所有人开始想办法绕过二次验证
    }

    @Test
    @DisplayName("单台可逆档：逐台点名不加闸，范围升到水站或全部才加闸")
    void reversibleTypesEscalateWithScope() {
        for (DeviceEnum.CmdType type : new DeviceEnum.CmdType[] {
            DeviceEnum.CmdType.LOCK, DeviceEnum.CmdType.UNLOCK,
            DeviceEnum.CmdType.PARAM_SYNC, DeviceEnum.CmdType.REBOOT }) {
            assertFalse(DeviceCommandRisk.requireSafe(type.getValue(), SCOPE_DEVICES),
                    type.getDesc() + " 逐台点名沿用权限+日志，不加闸");
            assertTrue(DeviceCommandRisk.requireSafe(type.getValue(), SCOPE_STATION),
                    type.getDesc() + " 范围到水站必须加闸");
            assertTrue(DeviceCommandRisk.requireSafe(type.getValue(), SCOPE_ALL),
                    type.getDesc() + " 范围到全部必须加闸");
        }
    }

    @Test
    @DisplayName("范围缺失按单台处理，但出水类无论如何都不受理")
    void nullScopeAndOrderOnly() {
        assertFalse(DeviceCommandRisk.requireSafe(DeviceEnum.CmdType.LOCK.getValue(), null));
        assertTrue(DeviceCommandRisk.requireSafe(DeviceEnum.CmdType.PRICE_SYNC.getValue(), null),
                "恒加闸档不因范围缺失降档");
        // 出水类走到人工下发路径只可能是上游白名单破了，fail-closed 而不是默默放行
        assertThrows(JbkException.class, () -> DeviceCommandRisk.requireSafe(
                DeviceEnum.CmdType.START_DISPENSE.getValue(), SCOPE_DEVICES));
    }

    @Test
    @DisplayName("单发白名单由档位派生：恰为 {3,4,5,6,7}，恒加闸档结构上进不来")
    void consoleWhitelistIsDerivedAndExcludesGated() {
        Set<Integer> allowed = DeviceCommandRisk.consoleAllowedTypes();
        assertEquals(Set.of(
                DeviceEnum.CmdType.QUERY_STATUS.getValue(),
                DeviceEnum.CmdType.LOCK.getValue(),
                DeviceEnum.CmdType.UNLOCK.getValue(),
                DeviceEnum.CmdType.PARAM_SYNC.getValue(),
                DeviceEnum.CmdType.REBOOT.getValue()), allowed,
                "派生结果必须与改造前的手写集合逐位相同");
        assertFalse(allowed.contains(DeviceEnum.CmdType.STOP_DISPENSE.getValue()));
        assertFalse(allowed.contains(DeviceEnum.CmdType.PRICE_SYNC.getValue()));
        assertFalse(allowed.contains(DeviceEnum.CmdType.START_DISPENSE.getValue()));
    }

    @Test
    @DisplayName("每个指令类型都必须有档位：新增指令不登记即在此显形")
    void everyCommandTypeHasATier() {
        for (DeviceEnum.CmdType type : DeviceEnum.CmdType.values()) {
            assertNotNull(type.getTier(), type.name() + " 缺少风险档位");
        }
    }
}

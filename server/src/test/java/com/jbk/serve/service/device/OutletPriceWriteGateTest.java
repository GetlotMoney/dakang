package com.jbk.serve.service.device;

import com.jbk.serve.service.trade.WaterBillingMath;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 出水口单价写入侧闸门（G7-B03）：写入侧必须与读侧共用 {@link WaterBillingMath#requireOutletPrice}，
 * 另写宽松校验会让最松的那处重新成为错误计价入口。
 */
class OutletPriceWriteGateTest {

    private static String rejectReason(String price) {
        return assertThrows(JbkException.class,
                () -> WaterBillingMath.requireOutletPrice(price)).getMessage();
    }

    @Test
    @DisplayName("合法整数分/升原样通过，含 0 与上限边界")
    void acceptsCanonicalIntegerFenPerLiter() {
        assertEquals(0, WaterBillingMath.requireOutletPrice("0"));
        assertEquals(20, WaterBillingMath.requireOutletPrice("20"));
        assertEquals(WaterBillingMath.UNIT_PRICE_MAX_FEN_PER_LITER,
                WaterBillingMath.requireOutletPrice(
                        String.valueOf(WaterBillingMath.UNIT_PRICE_MAX_FEN_PER_LITER)));
    }

    @Test
    @DisplayName("超过上限一分即拒（PC 输入框上限已同步收到 1000 元/升）")
    void rejectsOneFenOverTheCap() {
        rejectReason(String.valueOf(WaterBillingMath.UNIT_PRICE_MAX_FEN_PER_LITER + 1));
    }

    @Test
    @DisplayName("非数字、负数、小数、前导零、内部空白与全角数字一律拒绝")
    void rejectsMalformedText() {
        for (String bad : new String[] { "abc", "-100", "1.5", "0.2", "+20", "2 0",
            "", "  ", "007", "２０", "20分", "0x14" }) {
            assertThrows(JbkException.class, () -> WaterBillingMath.requireOutletPrice(bad),
                    "应拒绝非法单价文本：[" + bad + "]");
        }
    }

    @Test
    @DisplayName("前后空白按人工录入噪声 trim 掉，不当成语义差异")
    void trimsSurroundingWhitespace() {
        // 读侧把它收紧成拒绝，会让库里已有的带空白配置突然取不了水——错误在配置端，
        // 代价却落在正在接水的用户身上
        assertEquals(20, WaterBillingMath.requireOutletPrice(" 20"));
        assertEquals(20, WaterBillingMath.requireOutletPrice("20 "));
    }

    @Test
    @DisplayName("null 按非法处理，不得当成 0 分/升免费出水")
    void rejectsNull() {
        // 把缺失读成 0 的后果是整台设备免费出水，且账面上看起来一切正常
        assertThrows(JbkException.class, () -> WaterBillingMath.requireOutletPrice(null));
    }

    @Test
    @DisplayName("拒因带上原始文本，运营看得出自己填了什么")
    void rejectionCarriesTheOffendingText() {
        // 只说「配置非法」而不回显值，运营改三次也不知道错在哪一位
        org.junit.jupiter.api.Assertions.assertTrue(rejectReason("abc").contains("abc"));
    }
}

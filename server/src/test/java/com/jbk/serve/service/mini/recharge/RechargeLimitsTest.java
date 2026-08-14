package com.jbk.serve.service.mini.recharge;

import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 创单输入上限与溢出（L2 契约 §4.4）。
 * 这是「脏套餐数据不得被写进订单快照」的最后一道闸；一期不截断、不纠正，越界即拒。
 * 用例以刚好通过 / 刚好越界成对出现，防止有人把边界悄悄挪一格。
 */
class RechargeLimitsTest {

    /** 合法基线：水量套餐，单价 20.00 分/升，永久有效。 */
    private WsPackage base() {
        return new WsPackage()
                .setId(3L)
                .setPackageName("季卡 100L")
                .setPayAmount(9900L)
                .setWaterMl(100_000L)
                .setBonusAmount(0L)
                .setUnitPriceSnap("20.00")
                .setPackageStatus(1)
                .setExpireDays(null);
    }

    private void pass(WsPackage p, String why) {
        assertDoesNotThrow(() -> RechargeLimits.validatePackage(p), why);
    }

    private void reject(WsPackage p, String why) {
        assertThrows(JbkException.class, () -> RechargeLimits.validatePackage(p), why);
    }

    /**
     * 断言拒绝理由，而不只是"抛了个异常"。
     * 缺失类字段若被静默当作 0 继续往下走，往往仍会被后面的区间检查兜住而看不出来，
     * 但报给前端的原因就变成了"超出范围"，运营按错误提示去改配置永远改不对。
     */
    private void rejectBecause(WsPackage p, String expectMsgPart, String why) {
        JbkException e = assertThrows(JbkException.class, () -> RechargeLimits.validatePackage(p), why);
        assertTrue(e.getMsg() != null && e.getMsg().contains(expectMsgPart),
                why + "：拒绝理由应含「" + expectMsgPart + "」，实际为「" + e.getMsg() + "」");
    }

    // 套餐读不到就必须停在这里；放行会让后面用 null 套餐建出金额为 0 的订单
    @Test
    void nullPackageRejected() {
        reject(null, "套餐为 null 必须拒绝");
    }

    // 名称越界不拦，会把超长/空白名字写进 PACKAGE_SNAP，退款与对账时无法指认买的是什么
    @Test
    void packageNameLengthBoundary() {
        reject(base().setPackageName(null), "名称 null");
        reject(base().setPackageName(""), "名称空串");
        reject(base().setPackageName("   "), "纯空格 trim 后为空，不得当作有名字");
        pass(base().setPackageName("A"), "1 字符是下界，必须放行");
        pass(base().setPackageName("字".repeat(50)), "50 字符是上界，必须放行");
        reject(base().setPackageName("字".repeat(51)), "51 字符越界");
    }

    // 售价越界不拦，等于让运营配错的价直接进支付单，收错钱后只能靠人工退
    @Test
    void payAmountBoundary() {
        rejectBecause(base().setPayAmount(null), "套餐售价缺失", "售价 null 必须报「缺失」而不是被当作 0");
        reject(base().setPayAmount(0L), "0 分订单不成立");
        pass(base().setPayAmount(1L).setWaterMl(0L).setUnitPriceSnap("0"), "1 分是下界");
        pass(base().setPayAmount(1_000_000L), "10000 元是上界");
        reject(base().setPayAmount(1_000_001L), "超上界一分也要拒");
    }

    // 水量越界不拦，一次充值就能给出超出站点供水能力的额度，后续取水链没有第二道兜底
    @Test
    void waterMlBoundary() {
        rejectBecause(base().setWaterMl(null), "套餐水量缺失",
                "水量 null 必须报「缺失」；被当作 0 会让水量套餐悄悄降级成纯金额套餐");
        reject(base().setWaterMl(-1L), "负水量");
        pass(base().setWaterMl(0L).setUnitPriceSnap("0"), "0 是合法的纯金额套餐");
        pass(base().setWaterMl(50_000_000L), "50000 升是上界");
        reject(base().setWaterMl(50_000_001L), "超上界一毫升也要拒");
    }

    // 赠送额越界不拦，等于凭一条套餐配置无上限地发钱
    @Test
    void bonusAmountBoundary() {
        pass(base().setBonusAmount(null), "赠送额 null 视为 0，不得因此拒绝正常套餐");
        reject(base().setBonusAmount(-1L), "负赠送额（会变成扣钱）");
        pass(base().setBonusAmount(1_000_000L), "10000 元是上界");
        reject(base().setBonusAmount(1_000_001L), "超上界一分也要拒");
    }

    // 单价快照是退款折算与对账的唯一依据；放进科学计数法或三位小数，后续按分计算会静默丢精度
    @Test
    void unitPriceSnapFormatAndBoundary() {
        reject(base().setUnitPriceSnap(null), "单价缺失");
        reject(base().setUnitPriceSnap(""), "单价空串");
        reject(base().setUnitPriceSnap("1e3"), "科学计数法必须拒绝，否则 1e3 与 1000 两种写法对不上账");
        reject(base().setUnitPriceSnap("1.234"), "三位小数超出分的精度");
        reject(base().setUnitPriceSnap("-1"), "负单价");
        pass(base().setUnitPriceSnap("0.01"), "两位小数下界");
        pass(base().setUnitPriceSnap("100000"), "100000 分/升是上界");
        reject(base().setUnitPriceSnap("100000.01"), "超上界");
    }

    // 这条是「按水量记账」与「按金额记账」两条账不串味的分界：
    // 判反了会出现水量套餐单价为 0（退款折算除零/白送）或纯金额套餐带单价（凭空多出水量口径）
    @Test
    void waterPackageNeedsPositiveUnitPrice() {
        pass(base().setWaterMl(1L).setUnitPriceSnap("0.01"), "水量套餐单价 > 0 才成立");
        reject(base().setWaterMl(1L).setUnitPriceSnap("0"), "水量套餐单价为 0 必须拒绝");
        reject(base().setWaterMl(1L).setUnitPriceSnap("0.00"), "0.00 同样是 0，不得因写法不同放行");
    }

    @Test
    void amountOnlyPackageNeedsZeroUnitPrice() {
        pass(base().setWaterMl(0L).setUnitPriceSnap("0"), "纯金额套餐单价必须恰为 0");
        pass(base().setWaterMl(0L).setUnitPriceSnap("0.00"), "0.00 按数值视为 0，必须放行");
        reject(base().setWaterMl(0L).setUnitPriceSnap("0.01"), "纯金额套餐带非零单价必须拒绝");
    }

    /**
     * 有效期数值边界。
     *
     * <p>改走 {@code validateSnapshotValues}：D-213 之后套餐创建路径一律拒绝带有效期的付费套餐
     * （见 {@link #paidPackageMustNotCarryExpiry}），而 ws_package 的售价下限是 1 分、不存在 0 元套餐，
     * 因此「有效期合法值」在**创建路径上已不可达**。但历史订单快照里仍可能带着有效期，
     * 回放与权益入账要读它们，数值边界必须继续守住，故边界覆盖挪到快照校验这条仍然活着的路径。</p>
     */
    @Test
    void expireDaysBoundary() {
        snapshotPass(null, "null = 永久，是合法配置");
        snapshotReject(0, "0 天等于创建即过期");
        snapshotReject(-1, "负天数");
        snapshotPass(1, "1 天是下界");
        snapshotPass(3650, "3650 天是上界");
        snapshotReject(3651, "超上界");
    }

    private void snapshotPass(Integer days, String why) {
        assertDoesNotThrow(() -> RechargeLimits.validateSnapshotValues(
                "季卡 100L", 9900L, 100_000L, 0L, "20.00", days), why);
    }

    private void snapshotReject(Integer days, String why) {
        assertThrows(JbkException.class, () -> RechargeLimits.validateSnapshotValues(
                "季卡 100L", 9900L, 100_000L, 0L, "20.00", days), why);
    }

    // 极端值必须以业务异常收场：ArithmeticException 逃逸会变成 500 且无业务语义，
    // 静默回绕成负数则会把负余额写进库。注意此处实际由 PAY_AMOUNT_MAX 先行拦下（见 notesForAudit）。
    @Test
    void hugeAmountsNeverEscapeAsArithmeticOrWrapAround() {
        reject(base().setPayAmount(Long.MAX_VALUE).setBonusAmount(Long.MAX_VALUE),
                "接近 Long.MAX 的金额必须抛 JbkException，不得逃逸 ArithmeticException 或回绕成负数");
        reject(base().setPayAmount(Long.MAX_VALUE).setBonusAmount(1L), "单边极大值同样必须拒绝");
        reject(base().setPayAmount(1L).setBonusAmount(Long.MAX_VALUE), "赠送额极大值同样必须拒绝");
    }

    // §2.3 权益为空：水量与金额两个 credit 同时为 0 的订单没有任何可交付内容，
    // 放行会产生「付了钱什么都没到账」的幽灵订单。此处由 PAY_AMOUNT_MIN 先行拦下（见 notesForAudit）。
    @Test
    void emptyEntitlementRejected() {
        reject(base().setWaterMl(0L).setPayAmount(0L).setBonusAmount(0L).setUnitPriceSnap("0"),
                "水量 0 且金额 0 的套餐必须拒绝");
        pass(base().setWaterMl(0L).setPayAmount(1L).setBonusAmount(0L).setUnitPriceSnap("0"),
                "只要有 1 分权益就成立，不得误伤");
    }

    /** D-213：付费套餐一律永久有效，不得设有效期（此前只在文档、代码零守卫）。 */
    @Test
    void paidPackageMustNotCarryExpiry() {
        rejectBecause(base().setExpireDays(365), "付费套餐不得设置有效期",
                "付费套餐带有效期必须拒绝，且理由要说清是 D-213 而非「超出范围」");
        reject(base().setExpireDays(1), "1 天也不行——判据是有无，不是长短");
        // ws_package 售价下限为 1 分，不存在 0 元套餐——赠卡走独立发卡服务，不经本表。
        // 因此本表内「付费 + 永久」是唯一可达形态，正常路径不受影响。
        pass(base().setExpireDays(null), "付费永久卡是唯一可达的付费形态");
        // 历史快照仍可带有效期，不得被本闸误伤（回放与权益入账要读它们）
        assertDoesNotThrow(() -> RechargeLimits.validateSnapshotValues(
                "历史季卡", 9900L, 100_000L, 0L, "20.00", 365),
                "历史有限期快照只做兼容读取，不适用新规则");
    }
}

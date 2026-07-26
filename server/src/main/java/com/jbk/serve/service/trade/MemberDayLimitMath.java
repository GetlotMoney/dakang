package com.jbk.serve.service.trade;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.tool.data.trade.po.WsOrder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 成员单日取水限额的纯计算口径（CARD-MEMBER，不建日统计表）。
 *
 * <p>占用统计 = 当天（业务时区 Asia/Shanghai，部署基线保证 JVM/DB 时钟一致，
 * 窗口直接由服务端 now 字符串派生）同一成员在同一张卡上的取水订单按状态折算：</p>
 * <ul>
 *   <li>2 已支付 / 3 出水中：按 PLAN_ML（结果未定，按预扣全额占用）；</li>
 *   <li>4 已完成 / 8 部分退款：有合法 ACTUAL_ML 用实际值，否则按 PLAN_ML；</li>
 *   <li>6 异常待补偿：有实际值用实际值，否则保守按 PLAN_ML（异常单不能白占限额之外的量）；</li>
 *   <li>7 已退款：按 0（全额退，未真实取水）；</li>
 *   <li>1 待支付 / 5 已取消：不计；</li>
 *   <li>未知状态：保守按 PLAN_ML（fail-closed——宁可少放行，不可放限额漏洞）。</li>
 * </ul>
 *
 * <p>并发安全不在本类：靠取水事务先锁 uk_card_member_user 成员关系行，把同成员同卡的
 * 并发下单串行化后再调用本类统计。溢出一律 {@link Math#addExact} 拒绝，不得回绕放行。</p>
 */
public final class MemberDayLimitMath {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private MemberDayLimitMath() {
    }

    /** 当天窗口起点（含）：yyyyMMdd000000。now 必须是合法 yyyyMMddHHmmss。 */
    public static String dayStart(String now) {
        return LocalDate.parse(now.substring(0, 8), DAY).format(DAY) + "000000";
    }

    /** 次日窗口起点（不含）：yyyyMMdd000000（跨月/跨年由 LocalDate 保证正确进位）。 */
    public static String dayEnd(String now) {
        return LocalDate.parse(now.substring(0, 8), DAY).plusDays(1).format(DAY) + "000000";
    }

    /**
     * 按状态折算当天已占用水量（毫升）。任何一笔的折算值为负按 0 计；
     * 累加使用 {@link Math#addExact}，溢出直接抛出由调用方按拒绝处理。
     */
    public static long occupiedMl(List<WsOrder> orders) {
        long occupied = 0L;
        if (orders == null) {
            return occupied;
        }
        for (WsOrder order : orders) {
            occupied = Math.addExact(occupied, occupiedOf(order));
        }
        return occupied;
    }

    /**
     * 是否允许本次取水：已占用 + 本次计划量 <= 单日限额。
     * 溢出视为不允许（fail-closed）。
     *
     * @param occupiedMl 当天已占用（毫升）
     * @param planMl     本次计划水量（毫升）
     * @param dayLimitMl 单日限额（毫升，调用方保证非空）
     */
    public static boolean allows(long occupiedMl, long planMl, long dayLimitMl) {
        try {
            return Math.addExact(occupiedMl, planMl) <= dayLimitMl;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    /** 剩余额度（毫升）：限额-已占用，下限 0（供预检/列表展示，不参与事务判定）。 */
    public static long remaining(long dayLimitMl, long occupiedMl) {
        long remaining = dayLimitMl - occupiedMl;
        return Math.max(0L, remaining);
    }

    private static long occupiedOf(WsOrder order) {
        Integer status = order.getOrderStatus();
        long plan = nonNegative(order.getPlanMl());
        Long actual = order.getActualMl();
        boolean legalActual = ObjectUtil.isNotNull(actual) && actual >= 0;
        if (ObjectUtil.equal(status, 1) || ObjectUtil.equal(status, 5)) {
            // 待支付/已取消：未发生取水占用
            return 0L;
        }
        if (ObjectUtil.equal(status, 7)) {
            // 已退款（零出水全退）：按 0
            return 0L;
        }
        if (ObjectUtil.equal(status, 4) || ObjectUtil.equal(status, 8) || ObjectUtil.equal(status, 6)) {
            // 终态/异常：有合法实际量用实际，否则保守按计划量
            return legalActual ? actual : plan;
        }
        // 2 已支付 / 3 出水中 / 未知状态：按计划量保守占用
        return plan;
    }

    private static long nonNegative(Long value) {
        return ObjectUtil.isNull(value) || value < 0 ? 0L : value;
    }
}

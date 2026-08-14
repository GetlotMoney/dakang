package com.jbk.serve.service.settlement.split;

import com.jbk.tool.consts.settlement.SplitV2Enum.AttributionSource;
import com.jbk.tool.consts.settlement.SplitV2Enum.BasisLine;
import com.jbk.tool.consts.settlement.SplitV2Enum.RegionLevel;
import com.jbk.tool.consts.settlement.SplitV2Enum.RoleCode;
import com.jbk.tool.exception.JbkException;

import java.util.ArrayList;
import java.util.List;

/**
 * 分润 V2 纯计算器（E2E-08 S1，全仓唯一公式实现，任务书 5.3）。
 *
 * <p>纯函数：无 Spring、无数据库、无时钟——同样输入永远得到同样组件列表。
 * 公式只在这里，Service/Controller/前端一律只消费输出，绝不复制第二份。</p>
 *
 * <h2>会议规则 → 代码位置</h2>
 * <ul>
 *   <li>M3 双基数分离：一次调用只算一条线，两线各自调用，没有任何合并入口</li>
 *   <li>M6 一级推荐：推荐人是显式输入，空即无组件，{@code #} 不存在任何向上递归</li>
 *   <li>M7 级差：{@link #regionComponents}——上级实得 = 自身累计 − 最近下级累计</li>
 *   <li>M8 血缘冻结：计算器根本不接收行政区划输入，想按地缘算都没有原料</li>
 *   <li>M9 公域裁剪：{@link AttributionSource#PUBLIC_UNASSIGNED} 时推荐/区域组件不生成</li>
 * </ul>
 *
 * <h2>断层语义（D-428，2026-08-14 冻结，取代原 S1 阻断位）</h2>
 * <p>区域链接受任意子集：直接归属层（最低在场层）拿本层累计全额，其余在场层实得
 * = 自身累计 − 最近在场下级累计；缺席层的级差切片被最近在场上级吸收，在场层之上
 * 无人时差额落平台余数。前缀链（空/省/省市/省市区县）的结果与 S1 语义逐分不变，
 * 放开的只是断层与缺上级形态——不再存在因链形态拒算而打挂完成动作的路径。</p>
 *
 * @author dakang
 * @since 2026-08-06
 */
public final class SplitPlanCalculator {

    private SplitPlanCalculator() {
    }

    /** 平台余数行的收益人哨兵（沿用 V1：NULL 不参与唯一约束）。 */
    public static final long PLATFORM_SENTINEL_USER = 0L;
    /** 平台余数行的比例占位：余数不是比例，写任何万分比都是伪造。 */
    public static final int REMAINDER_RATE = -1;

    /**
     * 计算一条基数线的全部分润组件。
     *
     * @return 组件列表；基数为 0 时返回空列表（无可分不是错误）。
     *         金额为 0 的非平台组件不生成——组件表是「拿到钱」的证据，
     *         比例为 0 的角色等同未参与；恒等式 Σ组件 = 基数 不受影响
     */
    public static List<SplitComponentDraft> calculate(SplitPlanSnapshot plan, SplitCalcInput in) {
        if (plan == null || in == null) {
            throw new JbkException("分润计算输入缺失");
        }
        plan.validate();
        if (in.line() == null || in.attributionSource() == null) {
            throw new JbkException("分润基数线与归属来源不能为空");
        }
        if (in.orderNo() == null || in.orderNo().isBlank()) {
            throw new JbkException("分润计算缺少订单号");
        }
        if (in.basisAmountFen() < 0) {
            throw new JbkException("分润基数不能为负数");
        }
        if (in.basisAmountFen() == 0) {
            return List.of();
        }

        List<SplitComponentDraft> out = new ArrayList<>();
        if (in.line() == BasisLine.WATER_SALE) {
            waterComponents(plan, in, out);
        }
        else {
            deliveryComponents(plan, in, out);
        }
        appendPlatformRemainder(plan, in, out);
        return List.copyOf(out);
    }

    // ==================== 售水线 ====================

    private static void waterComponents(SplitPlanSnapshot plan, SplitCalcInput in,
                                        List<SplitComponentDraft> out) {
        // 机主：基础流水收益（M1）。归属缺失按「无此角色」处理，份额落平台余数——
        // 是否阻断由 S2 接线的调用方决定，纯计算器不替业务定夺数据修复策略。
        if (in.ownerUserId() != null) {
            add(out, plan, in, RoleCode.WATER_OWNER, in.ownerUserId(),
                    plan.itemOf(BasisLine.WATER_SALE, RoleCode.WATER_OWNER).rateBp());
        }
        boolean attributable = in.attributionSource() != AttributionSource.PUBLIC_UNASSIGNED;
        // 一级推荐（M6/M10）：显式输入，空即无。公域未分配一律裁剪（M9）。
        if (attributable && in.ownerDirectReferrerUserId() != null) {
            add(out, plan, in, RoleCode.WATER_DIRECT_REFERRER, in.ownerDirectReferrerUserId(),
                    plan.itemOf(BasisLine.WATER_SALE, RoleCode.WATER_DIRECT_REFERRER).rateBp());
        }
        if (attributable) {
            regionComponents(plan, in, out);
        }
    }

    /**
     * 区域级差（M7/D-405，断层语义 D-428 冻结）：比例表是各层<b>累计上限</b>，实得是级差。
     *
     * <p>统一公式：<b>直接归属层（最低在场层）拿本层累计全额；其余在场层实得 =
     * 自身累计 − 最近在场下级累计</b>。链 {省,市,区县}：区县拿区县累计，市拿市−区县，
     * 省拿省−市（矩阵 4）。链 {省}：省拿省级累计全额（矩阵 5）。
     * 缺席层不产生行，其级差切片被最近在场上级自然吸收（{省,区县} 时省拿省−区县）；
     * 在场层之上无人时，其上的差额落平台余数（{区县} 时省市差额归公司）——
     * 与 8.6 会议「省直招省拿全额」「级差=上级吃下级没拿的」同构，全链缺席才全归公司。
     * 绝不把三层累计相加——那会把 10%+8%+5% 分成 23%，正是会议点名要防的错。</p>
     */
    private static void regionComponents(SplitPlanSnapshot plan, SplitCalcInput in,
                                         List<SplitComponentDraft> out) {
        List<SplitCalcInput.RegionNode> chain = in.regionChain() == null ? List.of() : in.regionChain();
        if (chain.isEmpty()) {
            return;
        }
        Long province = null;
        Long city = null;
        Long county = null;
        for (SplitCalcInput.RegionNode node : chain) {
            if (node == null || node.level() == null || node.userId() == null) {
                throw new JbkException("区域链节点缺失层级或收款人");
            }
            switch (node.level()) {
                case PROVINCE -> {
                    if (province != null) {
                        throw new JbkException("同层出现多个区域服务商：省级");
                    }
                    province = node.userId();
                }
                case CITY -> {
                    if (city != null) {
                        throw new JbkException("同层出现多个区域服务商：市级");
                    }
                    city = node.userId();
                }
                case COUNTY -> {
                    if (county != null) {
                        throw new JbkException("同层出现多个区域服务商：区县级");
                    }
                    county = node.userId();
                }
                default -> throw new JbkException("区域链不允许 NONE 层级");
            }
        }
        int provinceCum = plan.itemOf(BasisLine.WATER_SALE, RoleCode.REGION_PROVINCE).rateBp();
        int cityCum = plan.itemOf(BasisLine.WATER_SALE, RoleCode.REGION_CITY).rateBp();
        int countyCum = plan.itemOf(BasisLine.WATER_SALE, RoleCode.REGION_COUNTY).rateBp();

        // 自低向高遍历在场层：最低在场层拿本层累计全额，其余在场层拿「自身累计 −
        // 最近在场下级累计」。缺席层无行，级差切片被上方最近在场层吸收（D-428）。
        int lowerCum = -1;
        if (county != null) {
            add(out, plan, in, RoleCode.REGION_COUNTY, county, countyCum);
            lowerCum = countyCum;
        }
        if (city != null) {
            add(out, plan, in, RoleCode.REGION_CITY, city, lowerCum < 0 ? cityCum : cityCum - lowerCum);
            lowerCum = cityCum;
        }
        if (province != null) {
            add(out, plan, in, RoleCode.REGION_PROVINCE, province,
                    lowerCum < 0 ? provinceCum : provinceCum - lowerCum);
        }
    }

    // ==================== 配送费线 ====================

    private static void deliveryComponents(SplitPlanSnapshot plan, SplitCalcInput in,
                                           List<SplitComponentDraft> out) {
        // 配送费必须有承接人：基数 >0 而无配送员，静默全归平台等于吞掉配送员的钱
        if (in.courierUserId() == null) {
            throw new JbkException("配送费分润缺少配送员，拒绝计算");
        }
        add(out, plan, in, RoleCode.DELIVERY_COURIER, in.courierUserId(),
                plan.itemOf(BasisLine.DELIVERY_FEE, RoleCode.DELIVERY_COURIER).rateBp());
    }

    // ==================== 公共 ====================

    private static void add(List<SplitComponentDraft> out, SplitPlanSnapshot plan,
                            SplitCalcInput in, RoleCode role, long receiver, int rateBp) {
        if (rateBp < 0 || rateBp > 10_000) {
            // 级差相减后仍必须落在 0..10000：负数=计划倒挂漏网，防御复验
            throw new JbkException("生效比例越界：" + role + "=" + rateBp);
        }
        long amount = mulDivFloor(in.basisAmountFen(), rateBp);
        if (amount == 0) {
            return;
        }
        out.add(new SplitComponentDraft(in.line(), in.basisAmountFen(), role, receiver,
                rateBp, amount, plan.planVersion(), in.attributionSource(),
                componentKey(in, role)));
    }

    /**
     * 金额 = 基数 × 万分比 / 10000，向下取整。
     *
     * <p>{@code Math.multiplyExact} 抓 long 溢出（矩阵 14）：直接 {@code a * b}
     * 溢出后除法会得到一个看似正常的错数，比抛异常危险得多——这是任务书
     * 明令禁止「直接 amountFen * rateBp 后再除」的原因。溢出即 fail-closed。</p>
     */
    static long mulDivFloor(long basisFen, int rateBp) {
        try {
            return Math.multiplyExact(basisFen, rateBp) / 10_000;
        }
        catch (ArithmeticException e) {
            throw new JbkException("分润金额计算溢出：基数 " + basisFen + " 分 × " + rateBp + " 万分比");
        }
    }

    /**
     * 平台余数行（工程规则 7）：基数 − Σ非平台，恒等式的封口（矩阵 13）。
     * 余数为 0 时不生成行；为负说明上游校验漏网，fail-closed 绝不写负数行。
     */
    private static void appendPlatformRemainder(SplitPlanSnapshot plan, SplitCalcInput in,
                                                List<SplitComponentDraft> out) {
        long assigned = 0;
        for (SplitComponentDraft d : out) {
            assigned = Math.addExact(assigned, d.splitAmountFen());
        }
        long remainder = in.basisAmountFen() - assigned;
        if (remainder < 0) {
            throw new JbkException("非平台分润合计超出基数：基数 " + in.basisAmountFen()
                    + " 分，已分 " + assigned + " 分");
        }
        if (remainder == 0) {
            return;
        }
        out.add(new SplitComponentDraft(in.line(), in.basisAmountFen(),
                RoleCode.PLATFORM_REMAINDER, PLATFORM_SENTINEL_USER, REMAINDER_RATE,
                remainder, plan.planVersion(), in.attributionSource(),
                componentKey(in, RoleCode.PLATFORM_REMAINDER)));
    }

    /** 幂等键：同订单、同基数线、同角色恒一条（任务书 5.4 的唯一性口径）。 */
    private static String componentKey(SplitCalcInput in, RoleCode role) {
        return "SPLITV2:" + in.orderNo() + ":" + in.line() + ":" + role;
    }
}

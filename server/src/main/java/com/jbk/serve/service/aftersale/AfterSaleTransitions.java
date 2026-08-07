package com.jbk.serve.service.aftersale;

import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionStatus;
import com.jbk.tool.exception.JbkException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 售后执行状态机（E2E-04 规则 R0-6）——**唯一出处**。
 *
 * <pre>
 * ∅ ─insert─► 1待执行 ─claim─► 2执行中 ─成功─► 3已完成[终]
 *                │              │
 *                │              ├─可重试失败─► 4可重试 ─claim─► 2执行中
 *                │              │                  └─重试耗尽─► 5需人工对账[终*]
 *                │              └─不可重试失败───────────────► 5需人工对账[终*]
 *                └─运营终止─► 6已终止[终]   （2/4 亦可被终止）
 * </pre>
 *
 * <p><b>本类必须是 CAS 的数据源，而不是 CAS 的旁证。</b>
 * {@link #requireSources(int)} 返回目标态的合法前态集合，由 Mapper 接口以参数形式传给
 * WsAfterSaleActionMapper.xml，XML 用 {@code <foreach>} 渲染 {@code ACTION_STATUS IN (...)}。
 * XML 里**禁止**出现任何硬编码前态数字。</p>
 *
 * <p>反面教材（就在本仓）：{@code DeliveryTransitions.allowed()} 全仓只被单测引用，
 * 生产路径走的是另一套硬编码 WHERE，于是状态机有两份真相——一份给测试看、一份给库看，
 * 二者漂移时测试全绿而线上错。本类通过「CAS 的 IN 列表只能由 requireSources 生成」
 * 把这条路堵死：改矩阵即改生产 SQL，不存在只改一边的可能。</p>
 *
 * <p>纯函数、无状态、无 Spring 依赖，供 Service 与单测共用。
 * 本类只钉状态维度；调用方还必须在 WHERE 上叠加 ID、VERSION、DATA_STATUS 与归属条件
 * （铁律①：前态与归属全带齐，影响行必须 == 1）。</p>
 */
public final class AfterSaleTransitions {

    /**
     * 目标态 → 合法前态集合（全集；键不存在即该目标态不可由任何状态到达）。
     *
     * <p>用 LinkedHashSet 保序（升序登记）：前态集合会被渲染进 SQL 的 IN 列表，
     * 有序输出让生成的 SQL 文本稳定可比对，便于 Mock 单测按串断言与线上慢查询归并。</p>
     */
    private static final Map<Integer, Set<Integer>> SOURCES;

    static {
        Map<Integer, Set<Integer>> m = new LinkedHashMap<>();
        // claim：待执行首次认领，或到点的可重试再次认领（两态共用一条 CAS，避免两份状态判定）
        m.put(ActionStatus.PROCESSING.getValue(), sources(
                ActionStatus.PENDING, ActionStatus.RETRY_WAIT));
        // 成功：只能从执行中来。终态，不可再迁出
        m.put(ActionStatus.SUCCESS.getValue(), sources(
                ActionStatus.PROCESSING));
        // 可重试：只有执行中的基础设施类失败才降级到此（业务性失败直接转人工，不给重试机会）
        m.put(ActionStatus.RETRY_WAIT.getValue(), sources(
                ActionStatus.PROCESSING));
        // 转人工：执行中的不可重试失败，或可重试次数耗尽
        m.put(ActionStatus.RECONCILIATION_REQUIRED.getValue(), sources(
                ActionStatus.PROCESSING, ActionStatus.RETRY_WAIT));
        // 终止：未落账的三个非终态都可被运营终止；3已完成与5需人工对账不在此列——
        // 已完成的钱已动，终止它会让状态与账本背离；5 的收口是人工对账流程（包B），不走本边
        m.put(ActionStatus.TERMINATED.getValue(), sources(
                ActionStatus.PENDING, ActionStatus.PROCESSING, ActionStatus.RETRY_WAIT));
        SOURCES = Collections.unmodifiableMap(m);
    }

    private AfterSaleTransitions() {
    }

    /**
     * 目标态的合法前态集合——**CAS 的 IN 列表只能从这里取**。
     *
     * @param to 目标 ACTION_STATUS(1372)
     * @return 不可变有序集合，元素为合法前态值
     * @throws JbkException 目标态不可达（含未登记状态值）：fail-closed，绝不返回空集让 CAS 退化成无条件更新
     */
    public static Set<Integer> requireSources(int to) {
        Set<Integer> from = SOURCES.get(to);
        if (from == null || from.isEmpty()) {
            throw new JbkException("售后动作不支持该目标状态");
        }
        return from;
    }

    /**
     * 状态维度的转换合法性判定，供写前断言与单测全矩阵校验。
     * <p>与 {@link #requireSources(int)} 共用同一张表：断言口径与 CAS 口径不可能分叉。</p>
     */
    public static boolean allowed(int from, int to) {
        Set<Integer> sources = SOURCES.get(to);
        return sources != null && sources.contains(from);
    }

    private static Set<Integer> sources(ActionStatus... statuses) {
        Set<Integer> set = new LinkedHashSet<>();
        for (ActionStatus status : statuses) {
            set.add(status.getValue());
        }
        return Collections.unmodifiableSet(set);
    }
}

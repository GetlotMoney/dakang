package com.jbk.serve.service.aftersale;

import com.jbk.tool.consts.aftersale.AfterSaleEnum.ActionStatus;
import com.jbk.tool.exception.JbkException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 售后执行状态机全矩阵单测（E2E-04 包A R0-6）：6×6 逐格断言，白名单外一律非法。
 *
 * <p>{@link AfterSaleTransitions#requireSources(int)} 的返回值会被渲染进 CAS 的
 * {@code ACTION_STATUS IN (...)}，因此 {@link #unreachableTargetsThrowInsteadOfReturningEmptySet()}
 * 是本类里最要紧的一条：一旦它退化成返回空集，{@code <foreach>} 会渲染出空 IN 列表，
 * CAS 就变成了无条件更新——任意状态（含已完成）都会被改写。</p>
 */
class AfterSaleTransitionsTest {

    /**
     * 合法转换全集（from&gt;to）：
     * 1待执行/4可重试 → 2执行中（claim）；2 → 3已完成；2 → 4可重试；
     * 2/4 → 5需人工对账；1/2/4 → 6已终止。
     */
    private static final Set<String> ALLOWED = Set.of(
            "1>2", "4>2",
            "2>3",
            "2>4",
            "2>5", "4>5",
            "1>6", "2>6", "4>6");

    @Test
    void fullMatrixMatchesWhitelistExactly() {
        for (int from = 1; from <= 6; from++) {
            for (int to = 1; to <= 6; to++) {
                boolean expected = ALLOWED.contains(from + ">" + to);
                assertEquals(expected, AfterSaleTransitions.allowed(from, to),
                        "转换 " + from + "→" + to + " 判定错误");
            }
        }
    }

    /** 三个终态一律不可迁出：钱已动（3）、证据须存活（5）、已收口（6）。 */
    @Test
    void terminalStatesHaveNoOutgoingEdge() {
        for (int from : new int[]{ActionStatus.SUCCESS.getValue(),
                ActionStatus.RECONCILIATION_REQUIRED.getValue(),
                ActionStatus.TERMINATED.getValue()}) {
            for (int to = 1; to <= 6; to++) {
                assertFalse(AfterSaleTransitions.allowed(from, to),
                        "终态 " + from + " 不允许迁往 " + to);
            }
        }
    }

    /** 待执行（1）不是任何转换的目标态：入库即 1，只能由 insert 产生。 */
    @Test
    void pendingIsNeverATransitionTarget() {
        for (int from = 1; from <= 6; from++) {
            assertFalse(AfterSaleTransitions.allowed(from, ActionStatus.PENDING.getValue()),
                    "不允许把 " + from + " 退回待执行");
        }
        assertThrows(JbkException.class,
                () -> AfterSaleTransitions.requireSources(ActionStatus.PENDING.getValue()));
    }

    /**
     * 每个可达目标态的前态集合逐个钉死（含顺序：集合会被渲染成 SQL 的 IN 列表，
     * 有序输出让生成的 SQL 文本稳定可比对）。
     */
    @Test
    void requireSourcesReturnsExactPredecessorSetPerTarget() {
        assertEquals(List.of(1, 4),
                List.copyOf(AfterSaleTransitions.requireSources(ActionStatus.PROCESSING.getValue())),
                "claim 只认待执行与到点可重试");
        assertEquals(List.of(2),
                List.copyOf(AfterSaleTransitions.requireSources(ActionStatus.SUCCESS.getValue())),
                "成功只能从执行中来");
        assertEquals(List.of(2),
                List.copyOf(AfterSaleTransitions.requireSources(ActionStatus.RETRY_WAIT.getValue())),
                "只有执行中的基础设施失败才降级到可重试");
        assertEquals(List.of(2, 4),
                List.copyOf(AfterSaleTransitions.requireSources(
                        ActionStatus.RECONCILIATION_REQUIRED.getValue())),
                "转人工来自执行中的不可重试失败或重试耗尽");
        assertEquals(List.of(1, 2, 4),
                List.copyOf(AfterSaleTransitions.requireSources(ActionStatus.TERMINATED.getValue())),
                "只有未落账的三个非终态可被终止");
    }

    /** 3已完成 与 5需人工对账 不在可终止前态里：终止已完成会让状态与账本背离。 */
    @Test
    void terminateCannotSwallowSuccessOrReconciliation() {
        Set<Integer> sources = AfterSaleTransitions.requireSources(ActionStatus.TERMINATED.getValue());
        assertFalse(sources.contains(ActionStatus.SUCCESS.getValue()), "已完成不得被终止");
        assertFalse(sources.contains(ActionStatus.RECONCILIATION_REQUIRED.getValue()),
                "需人工对账的收口走人工流程，不走终止边");
    }

    /**
     * 不可达目标态必须抛，<b>绝不能返回空集</b>：空集会让 CAS 的 IN 列表退化成无条件更新。
     */
    @Test
    void unreachableTargetsThrowInsteadOfReturningEmptySet() {
        for (int to : new int[]{Integer.MIN_VALUE, -1, 0, 1, 7, 8, 99, Integer.MAX_VALUE}) {
            assertThrows(JbkException.class, () -> AfterSaleTransitions.requireSources(to),
                    "目标态 " + to + " 不可达，必须 fail-closed");
        }
        // 反面确认：可达目标态返回的集合一个都不为空
        for (int to : new int[]{2, 3, 4, 5, 6}) {
            assertFalse(AfterSaleTransitions.requireSources(to).isEmpty(),
                    "目标态 " + to + " 的前态集合不得为空");
        }
    }

    /** 未登记的状态值不得成为合法前态：脏值一旦被当作前态，CAS 就能从任意状态推进。 */
    @Test
    void unregisteredStatusIsNeverALegalSource() {
        for (int from : new int[]{-1, 0, 7, 99}) {
            for (int to = 1; to <= 6; to++) {
                assertFalse(AfterSaleTransitions.allowed(from, to),
                        "脏前态 " + from + " 不得迁往 " + to);
            }
        }
    }

    /** 返回的集合必须不可变：调用方一旦能就地增删，CAS 的 IN 列表就成了运行期可变量。 */
    @Test
    void returnedSourceSetIsImmutable() {
        Set<Integer> sources = AfterSaleTransitions.requireSources(ActionStatus.SUCCESS.getValue());
        assertThrows(UnsupportedOperationException.class, () -> sources.add(1));
        assertThrows(UnsupportedOperationException.class, () -> sources.clear());
        assertTrue(AfterSaleTransitions.requireSources(ActionStatus.SUCCESS.getValue()).contains(2),
                "被篡改尝试之后仍须保持原样");
    }
}

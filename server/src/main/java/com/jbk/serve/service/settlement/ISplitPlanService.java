package com.jbk.serve.service.settlement;

import com.jbk.serve.service.settlement.split.SplitPlanSnapshot;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.settlement.bo.SplitPlanBo;
import com.jbk.tool.data.settlement.po.WsSplitPlanItem;
import com.jbk.tool.data.settlement.po.WsSplitPlan;

import java.util.List;
import java.util.Optional;

/**
 * 分润 V2 整版计划（D-412）：发布即生效版本化，改比例=发新整版，历史版本只读。
 *
 * @author dakang
 * @since 2026-08-14
 */
public interface ISplitPlanService {

    /** 发布一版六方计划（整版校验通过才落库；两条商品线锁锚内串行，防并发交叉发布）。 */
    Long publish(SplitPlanBo bo, Long opUserId);

    /**
     * 订单创建时点的现行计划快照：EFFECT_TIME ≤ 该时点的最新生效整版。
     * empty = 该时点尚无 V2 计划（调用方回落 V1 口径，保证切换期连续性）。
     */
    Optional<SplitPlanSnapshot> activePlanAt(String bizTime);

    /** 计划分页（含头行；PC 展示用）。 */
    PageDataVo<WsSplitPlan> page(SplitPlanBo bo);

    /** 某计划的全部计划项（PC 展开用）。 */
    List<WsSplitPlanItem> itemsOf(Long planId);
}

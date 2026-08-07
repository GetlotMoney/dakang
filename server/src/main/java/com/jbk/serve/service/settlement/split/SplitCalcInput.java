package com.jbk.serve.service.settlement.split;

import com.jbk.tool.consts.settlement.SplitV2Enum.AttributionSource;
import com.jbk.tool.consts.settlement.SplitV2Enum.BasisLine;
import com.jbk.tool.consts.settlement.SplitV2Enum.RegionLevel;

import java.util.List;

/**
 * 分润 V2 计算输入（任务书 5.3）。
 *
 * <p>基数金额是<b>权威值</b>：调用方给什么算什么，计算器绝不反查订单、绝不重算优惠
 * ——实收口径（M4）由产生基数的一侧负责，这里只保证「给定基数下的分配正确」。</p>
 *
 * @param line                     基数线（水费 / 配送费两线各自独立调用，绝不合并，M3）
 * @param basisAmountFen           权威基数金额（整数分，≥0；0 表示本单该线无可分）
 * @param ownerUserId              机主；售水线可空（归属缺失时其份额自然落入平台余数）
 * @param ownerDirectReferrerUserId 机主的一级直接推荐人（M6/M10）；空即无此组件，
 *                                  <b>绝不</b>沿普通用户邀请链向上递归查找（规则 9）
 * @param regionChain              区域服务商链（自省向下的连续前缀，见 {@link RegionNode}）
 * @param attributionSource        归属来源；PUBLIC_UNASSIGNED 时推荐/区域组件一律裁剪（M9）
 * @param courierUserId            配送员；配送费线基数 &gt;0 时必填
 * @param orderNo                  订单号（组件键与追溯锚点）
 * @param bizTime                  业务时间 yyyyMMddHHmmss（证据留痕，不参与计算）
 */
public record SplitCalcInput(BasisLine line,
                             long basisAmountFen,
                             Long ownerUserId,
                             Long ownerDirectReferrerUserId,
                             List<RegionNode> regionChain,
                             AttributionSource attributionSource,
                             Long courierUserId,
                             String orderNo,
                             String bizTime) {

    /**
     * 区域链节点。
     *
     * <p>用列表而不是 Map：Map 的键唯一性会把「同层出现两个服务商」这种脏输入
     * 静默吞成一个，而那恰恰必须拒绝（矩阵 8）。列表保留原始输入，由计算器显式校验。</p>
     */
    public record RegionNode(RegionLevel level, Long userId) {
    }
}

package com.jbk.serve.service.settlement.split;

import com.jbk.tool.consts.settlement.SplitV2Enum.AttributionSource;
import com.jbk.tool.consts.settlement.SplitV2Enum.BasisLine;
import com.jbk.tool.consts.settlement.SplitV2Enum.RoleCode;

/**
 * 分润组件（计算输出，任务书 5.3）：回答「这个人从哪条基数、以什么角色、按什么比例拿多少钱」。
 *
 * <p>与 {@code ws_split_record} 的分工：组件是<b>计算证据</b>，record 是<b>付款状态机</b>。
 * S2 才把组件按收益人聚合成待入账行，本轮组件只落证据表（5.4）。</p>
 *
 * @param line              基数线
 * @param basisAmountFen    该线权威基数（每条组件都带，审计不用回查）
 * @param roleCode          角色编码
 * @param receiverUserId    收益人；平台余数行为 0 哨兵（NULL 不参与 MySQL 唯一约束，
 *                          V1 在 SettlementDbTest 实测踩过，沿用教训）
 * @param effectiveRateBp   实际生效万分比。级差角色=级差后的实得比例而非累计上限；
 *                          平台余数行=-1（余数不是比例，写任何比例都是伪造）
 * @param splitAmountFen    分得金额（整数分，向下取整；平台行吃余数）
 * @param planVersion       计划版本（证据锚点）
 * @param attributionSource 归属来源
 * @param componentKey      幂等键 SPLITV2:订单号:线:角色——同订单同线同角色恒一条（5.4）
 */
public record SplitComponentDraft(BasisLine line,
                                  long basisAmountFen,
                                  RoleCode roleCode,
                                  long receiverUserId,
                                  int effectiveRateBp,
                                  long splitAmountFen,
                                  String planVersion,
                                  AttributionSource attributionSource,
                                  String componentKey) {
}

package com.jbk.tool.data.trade.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 充值订单的<b>结构化</b>详情区块（L2 契约 v2 §9.2）。
 *
 * <p>契约明令<b>禁止直接向页面输出 {@code PACKAGE_SNAP} 原始 JSON</b>。原因不只是难看：
 * 让页面自己解析快照，等于把「什么算合法快照」这条判定复制到前端再实现一遍，
 * 两边一旦走样，用户看到的金额就可能与实际入账的金额不一致——而快照恰恰是充值的凭据。
 * 解析只在服务端做一次，页面只消费已校验的结构化字段。</p>
 */
@Data
@Accessors(chain = true)
@Schema(name = "MiniRechargeDetailVo", description = "充值订单结构化详情")
public class MiniRechargeDetailVo implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "快照是否可解析。false 时其余字段为空，页面必须提示数据异常而非展示残缺值")
    private Boolean snapshotValid;

    @Schema(description = "购买模式；FIRST_CARD=首次购卡，空=已有卡充值（仅来自订单快照）")
    private String purchaseMode;

    @Schema(description = "关联水卡ID；首次购卡未完成时为空")
    private Long cardId;

    @Schema(description = "关联水卡卡号；首次购卡未完成时为空")
    private String cardNo;

    @Schema(description = "关联水卡类型：1虚拟卡 2实体卡；首次购卡未完成时为空")
    private Integer cardType;

    @Schema(description = "首次购卡发行订单ID；已有卡充值或未完成购卡为空")
    private Long issueOrderId;

    @Schema(description = "服务端由规范化范围生成的展示摘要，不下发范围原文")
    private String scopeDescription;

    @Schema(description = "套餐名称（下单时快照）")
    private String packageName;

    @Schema(description = "支付金额(分)")
    private Long payAmountFen;

    @Schema(description = "到账水量(毫升)，0=纯余额套餐")
    private Long waterMl;

    @Schema(description = "赠送余额(分)")
    private Long bonusAmountFen;

    @Schema(description = "套餐有效期(天)，空=永久套餐")
    private Integer expireDays;

    @Schema(description = "支付状态(1342)")
    private Integer payStatus;

    @Schema(description = "支付来源：1微信 2Pay-Sim")
    private Integer paySource;

    @Schema(description = "支付事实处理态聚合，无事件为空")
    private String processingStatus;

    @Schema(description = "本订单唯一充值流水的余额变动(分)；未入账为空")
    private Long flowAmountChange;

    @Schema(description = "本订单唯一充值流水的水量变动(毫升)；未入账为空")
    private Long flowMlChange;

    @Schema(description = "入账后余额快照(分)；未入账为空")
    private Long flowAmountAfter;

    @Schema(description = "入账后水量快照(毫升)；未入账为空")
    private Long flowMlAfter;

    @Schema(description = "目标卡当前余额(分)")
    private Long cardBalanceFen;

    @Schema(description = "目标卡当前水量(毫升)")
    private Long cardBalanceMl;

    @Schema(description = "目标卡入账后有效期，空=永久")
    private String cardExpireTime;
}

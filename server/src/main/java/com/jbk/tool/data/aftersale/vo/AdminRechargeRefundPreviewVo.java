package com.jbk.tool.data.aftersale.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 充值/购卡退款依据预览 Vo（E2E-04 包D-5，REQ-061）。
 *
 * <h3>为什么必须有这个只读预览</h3>
 * <p>没有它，运营点「退款」时不知道会退多少钱、卡上会少多少权益、这张卡会不会被注销——
 * 而这三件事在点下去之后就是既成事实（钱原路退回支付账户，不可撤销）。
 * 与取水核账预览同一范式：<b>页面一列不推导</b>，全部字段由服务端按冻结公式算好下发；
 * 提交时也只提交订单ID与运营说明，金额不回传（回传就等于让前端参与定价）。</p>
 *
 * <p>{@code refundable=false} 时 {@code blockReason} 必非空，页面据此禁用按钮并原样展示原因——
 * 不可退的理由（历史聚合权益、已退过、权益已用尽、账本断裂）各自需要不同的人工动作。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "AdminRechargeRefundPreviewVo", description = "充值退款依据预览")
public class AdminRechargeRefundPreviewVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "充值订单ID")
    private String orderId;

    @Schema(description = "充值订单号")
    private String orderNo;

    @Schema(description = "订单状态(1341)：可退时恒为 4已完成")
    private Integer orderStatus;

    @Schema(description = "水卡ID")
    private String cardId;

    @Schema(description = "水卡号")
    private String cardNo;

    @Schema(description = "权益批次ID")
    private String batchId;

    @Schema(description = "批次来源(1375)：1首次购卡 2已有卡充值 3历史聚合（不可退） 4运营赠卡（恒不可退）")
    private Integer batchSourceType;

    @Schema(description = "批次状态(1374)")
    private Integer batchStatus;

    @Schema(description = "本批次实付金额(分)——折算的分子基准")
    private Long payAmountFen;

    @Schema(description = "本批次发放余额权益(分)=本金+赠送")
    private Long grantAmountFen;

    @Schema(description = "其中赠送部分(分)；赠送已消费部分不退款")
    private Long grantBonusFen;

    @Schema(description = "本批次发放水量权益(毫升)")
    private Long grantWaterMl;

    @Schema(description = "剩余余额权益(分)")
    private Long remainAmountFen;

    @Schema(description = "剩余水量权益(毫升)")
    private Long remainWaterMl;

    @Schema(description = "是否按水量套餐折算：true 用已用水量占比，false 用已消费金额直接抵扣")
    private Boolean waterPackage;

    @Schema(description = "已消费水量(毫升)")
    private Long usedWaterMl;

    @Schema(description = "预计可退金额(分)：服务端按冻结公式算定，提交时不接受回传")
    private Long refundableFen;

    @Schema(description = "预计从卡上冲减的余额(分)=批次剩余，与可退金额不是同一维度")
    private Long reverseFen;

    @Schema(description = "预计从卡上冲减的水量(毫升)=批次剩余")
    private Long reverseMl;

    @Schema(description = "退款完成后水卡是否会转注销（首购退款且卡上再无批次/成员/在途单时为 true）")
    private Boolean cardWillClose;

    @Schema(description = "是否可退：只有服务端明确 true 才放行，其余一律按不可退处理")
    private Boolean refundable;

    @Schema(description = "阻断原因：refundable=false 时必非空")
    private String blockReason;
}

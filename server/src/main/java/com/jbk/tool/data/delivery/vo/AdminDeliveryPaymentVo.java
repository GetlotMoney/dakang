package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 配送订单卡扣款流水核验 Vo（E2E-03 包C：DELIVERY:&lt;orderNo&gt; 业务幂等键）。
 * <p>资金链与履约链独立核验：flowStatus=mismatch 时只下发原因，不提供任何流水正向证据；
 * 核验项＝流水存在、归属本单、同卡同人、类型=配送扣减、金额=-订单总额、
 * 水量变动按支付方式核（payWay=2 必为 0；payWay=3 必为 -快照 waterMl，D-214）。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@Schema(name = "AdminDeliveryPaymentVo", description = "配送订单卡扣款流水核验")
public class AdminDeliveryPaymentVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "资金链核验：ok 一致 / mismatch 数据异常（隐藏流水证据）")
    private String flowStatus;

    @Schema(description = "flowStatus=mismatch 时的原因说明")
    private String flowReason;

    @Schema(description = "流水ID")
    private Long flowId;

    @Schema(description = "业务幂等键（固定 DELIVERY:订单号）")
    private String bizKey;

    @Schema(description = "金额变动(分)，扣款为负；payWay=3 时即 -配送费")
    private Long amountChangeFen;

    @Schema(description = "扣款后卡余额快照(分)（流水写入时冻结）")
    private Long amountAfterFen;

    @Schema(description = "水量变动(毫升)，payWay=3 抵扣为负；payWay=2 恒 0")
    private Long mlChange;

    @Schema(description = "扣减后卡水量快照(毫升)（流水写入时冻结）")
    private Long mlAfter;

    @Schema(description = "流水时间（与订单创建同源）")
    private String time;

    @Schema(description = "流水备注")
    private String remark;
}

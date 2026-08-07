package com.jbk.tool.data.aftersale.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * PC 取水异常核账依据（E2E-04 包A，只读预览）。
 *
 * <p>状态 6异常待补偿 有两条语义完全相反的来源，运营在页面上看到的都是同一个「6」：
 * 一条已经退过差（结算路径判定出水失败，退差流水已落，落 6 只是等复核），
 * 一条一分钱没退（出水指令链异常直接置位，只写了状态与原因）。把两者当成同一件事处理，
 * 前者会被二次退款、后者会被无偿完结。本 Vo 的存在就是把这条判别摊开给运营看，
 * 而不是让页面按订单状态自行猜测。</p>
 *
 * <p>{@code suggestTargetStatus} 是<b>服务端派生的建议值，仅供展示</b>：确认接口不接受
 * 前端回传目标状态，仍会在事务内按同一份规则重算。
 * {@code confirmable=false} 时 {@code blockReason} 必非空，是可直接展示给运营的精确原因。</p>
 *
 * @author dakang
 * @since 2026-07-29
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "AdminWaterAbnormalPreviewVo", description = "取水异常核账依据")
public class AdminWaterAbnormalPreviewVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "订单状态(1341)：可核账时恒为 6异常待补偿")
    private Integer orderStatus;

    @Schema(description = "支付方式(1346)：2水卡余额 3水卡水量")
    private Integer payWay;

    @Schema(description = "订单金额(分)：payWay=2 的预扣金额，也是退差金额的上界")
    private Long orderAmount;

    @Schema(description = "计划水量(毫升)")
    private Long planMl;

    @Schema(description = "实际水量(毫升)：结算路径回填；为空即从未结算过（来源B 的判别依据之一）")
    private Long actualMl;

    @Schema(description = "已退差金额(分)：本单 FLOW_TYPE=4 补偿流水的金额合计")
    private Long refundedFen;

    @Schema(description = "已退差水量(毫升)：本单 FLOW_TYPE=4 补偿流水的水量合计")
    private Long refundedMl;

    @Schema(description = "来源判别：A=已退差待复核 B=未退差 UNKNOWN=证据不足或账本断裂")
    private String sourceVerdict;

    @Schema(description = "来源判别说明（可直接展示）")
    private String sourceVerdictDesc;

    @Schema(description = "建议目标状态(1341)：零出水=7已退款，部分出水=4已完成；不可核账时为空。"
            + "仅供展示，确认接口不接受前端回传")
    private Integer suggestTargetStatus;

    @Schema(description = "是否可确认：false 时确认接口必然拒绝，页面据此禁用按钮")
    private Boolean confirmable;

    @Schema(description = "阻断原因：confirmable=false 时必非空")
    private String blockReason;
}

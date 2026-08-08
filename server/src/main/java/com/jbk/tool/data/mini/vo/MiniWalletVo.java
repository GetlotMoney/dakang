package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 机主收益钱包 Vo（E2E-08 / REQ-072）。分润净额口径，与 E2E-06 经营毛额口径并存不互改；
 * 不含任何下级用户身份字段（脱敏条款）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniWalletVo", description = "机主收益钱包（分润净额，账务口径）")
public class MiniWalletVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "可用分润余额(分)")
    private Long balanceFen;

    @Schema(description = "提现审核冻结中(分)")
    private Long frozenFen;

    @Schema(description = "冲减待补差额(分)（D-420）：>0 时提现暂不可用，后续分润入账优先补足；0=无差额")
    private Long clawbackDeficitFen;

    @Schema(description = "在途分润(分)：已产生、尚在冻结期未入账的分账合计（D-421）；防止延迟到账被当漏发")
    private Long pendingSplitFen;

    @Schema(description = "最早一笔在途分润的预计解冻时间(yyyyMMddHHmmss)；无在途时为空")
    private String earliestUnfreezeTime;

    @Schema(description = "近 50 条收益流水（倒序）")
    private List<Flow> flows;

    @Schema(description = "证据模式：real=真实库账务")
    private String evidenceMode;

    @Getter
    @Setter
    @Accessors(chain = true)
    @Schema(name = "MiniWalletVo.Flow", description = "收益流水行")
    public static class Flow implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "流水类型(1378)")
        private Integer flowType;

        @Schema(description = "变动金额(分)")
        private Long amountFen;

        @Schema(description = "变动后余额(分)")
        private Long afterFen;

        @Schema(description = "来源订单号（可追溯到结算与原始订单）")
        private String orderNo;

        @Schema(description = "时间")
        private String createTime;
    }
}

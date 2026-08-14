package com.jbk.tool.data.mall.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 小程序结算预览 Vo（E2E-09 S2）。
 *
 * <p>预览只做只读试算，不预占任何库存——预占发生在创单事务里。因此预览显示"可下单"
 * 并不保证创单必成功：中间可能被别人抢走库存，创单会再次原子校验并可能拒绝。
 * 这个差异是刻意的：为了预览而预占会让用户放弃结算时库存长期悬空。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MiniMallCheckoutVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "参与结算的行（沿用购物车行结构）")
    private List<MiniMallCartVo.CartLine> lines;

    @Schema(description = "商品金额(分)")
    private Long productAmountFen;

    @Schema(description = "配送费(分)：内部闭环期固定0")
    private Long deliveryFeeFen;

    @Schema(description = "应付总额(分)")
    private Long orderAmountFen;

    @Schema(description = "收货人姓名")
    private String receiverName;

    @Schema(description = "收货电话（脱敏）")
    private String maskedPhone;

    @Schema(description = "收货地区文本")
    private String receiverRegion;

    @Schema(description = "收货详细地址")
    private String receiverAddress;

    @Schema(description = "选中的前置仓名称（不可履约时为空）")
    private String warehouseName;

    @Schema(description = "是否可提交订单")
    private Boolean submittable;

    @Schema(description = "不可提交原因（可提交时为空）")
    private String blockReason;
}

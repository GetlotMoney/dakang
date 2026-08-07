package com.jbk.tool.data.settlement.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * PC 财务面统一入参（E2E-08 包E）：分账明细/对账/比例配置共用一 Bo——
 * 字段全可选，各端点只取所需并自行校验必填项。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "FinanceQueryBo", description = "财务面查询/操作入参")
public class FinanceQueryBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单ID筛选")
    private Long orderId;

    @Schema(description = "收款方类型(1377)")
    private Integer receiverType;

    @Schema(description = "分账状态(1345)")
    private Integer splitStatus;

    @Schema(description = "账期日（yyyyMMdd）")
    private String bizDate;

    @Schema(description = "差异分类(1379)")
    private Integer diffType;

    @Schema(description = "商品线(1376)")
    private Integer productLine;

    @Schema(description = "比例（万分比）")
    private Integer splitRate;

    @Schema(description = "生效时间（yyyyMMddHHmmss，缺省=当下）")
    private String effectTime;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "页码（≥1，缺省 1）")
    private Long current;

    @Schema(description = "页大小（1~100，缺省 20）")
    private Long size;

    /** 分页缺省与守卫收口（与全仓统一口径：size≤100、current 封顶防溢出）。 */
    public long pageOrDefault() {
        long value = current == null ? 1L : current;
        if (value <= 0 || value > 100_000) {
            throw new com.jbk.tool.exception.JbkException("页码不合法");
        }
        return value;
    }

    public long sizeOrDefault() {
        long value = size == null ? 20L : size;
        if (value <= 0 || value > 100) {
            throw new com.jbk.tool.exception.JbkException("页大小不合法");
        }
        return value;
    }
}

package com.jbk.tool.data.mall.bo;

import com.jbk.tool.data.PageBo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 商城售后台账查询（E2E-09 S4，PC）。
 *
 * <p>不接受 warehouseId 入参：可见范围由操作员的前置仓归属在服务端决定，
 * 前端传仓等于把数据范围交给调用方。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class MallAfterSaleQueryBo extends PageBo {

    @Schema(description = "售后单号（精确）")
    private String afterSaleNo;

    @Schema(description = "原订单号（精确）")
    private String orderNo;

    @Schema(description = "售后状态(1399)")
    private Integer afterSaleStatus;

    @Schema(description = "售后类型(1400)")
    private Integer afterSaleType;
}

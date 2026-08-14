package com.jbk.tool.data.mall.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商城订单查询入参（E2E-09 S2）。
 *
 * <p>小程序侧不接受 userId：数据范围恒由 Service 按会话人强制过滤。
 * PC 侧可按用户/订单号/状态检索，但同样只读。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@Accessors(chain = true)
public class MallOrderQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "页码")
    private Long current;

    @Schema(description = "每页条数")
    private Long size;

    @Schema(description = "订单状态(1393)：为空=全部")
    private Integer orderStatus;

    @Schema(description = "订单号精确匹配（PC 台账用）")
    private String orderNo;

    @Schema(description = "下单用户ID（PC 台账用；小程序侧忽略）")
    private Long userId;

    @Schema(description = "前置仓ID筛选（PC 台账用）")
    private Long warehouseId;
}

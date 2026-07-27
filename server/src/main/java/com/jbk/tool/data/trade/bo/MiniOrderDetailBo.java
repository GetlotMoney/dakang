package com.jbk.tool.data.trade.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 小程序订单详情查询入参。
 * <p>对齐 miniapp order.ts getOrderDetail(orderNo)：优先按 orderNo 查（前端列表项/路由参数均为 orderNo）；
 * orderId 作为可选备用（PC/内部按 ID 查）。二者至少传一个，归属校验由 Service 按会话登录人强制执行（铁律6）。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniOrderDetailBo", description = "小程序订单详情查询入参")
public class MiniOrderDetailBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单号（优先，对齐小程序 getOrderDetail(orderNo)）")
    private String orderNo;

    @Schema(description = "订单ID（可选备用；orderNo 为空时按此查）")
    private Long orderId;
}

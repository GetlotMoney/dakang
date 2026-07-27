package com.jbk.tool.data.mini.vo;

import com.jbk.tool.data.trade.vo.OrderDetailVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 小程序配送创单结果 Vo（E2E-03 包B；对齐 miniapp deliveryApi.createDeliveryOrder 返回 {order, task}）。
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@Schema(name = "MiniDeliveryCreateVo", description = "小程序配送创单结果")
public class MiniDeliveryCreateVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "配送订单详情（复用 mini 订单读模型，禁止另拼一套订单结构）")
    private OrderDetailVo order;

    @Schema(description = "配送任务（幂等命中时为既有任务）")
    private MiniDeliveryTaskVo task;
}

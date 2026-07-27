package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 配送履约时间线节点 Vo（下单→接单→离站→送达→三照签收）。
 * <p>done 由服务端按任务落库时间判定，前端不自行推导节点完成态。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@Schema(name = "AdminDeliveryTimelineNodeVo", description = "配送履约时间线节点")
public class AdminDeliveryTimelineNodeVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "节点码：created/accept/depart/arrive/sign")
    private String node;

    @Schema(description = "节点名称")
    private String nodeLabel;

    @Schema(description = "节点时间（未发生为空）")
    private String time;

    @Schema(description = "附加说明（如配送员姓名/实签数量）")
    private String detail;

    @Schema(description = "节点是否已发生")
    private Boolean done;
}

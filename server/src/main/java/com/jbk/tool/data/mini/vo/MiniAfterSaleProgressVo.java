package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 小程序订单详情中的售后进度投影（E2E-04，只读）。
 *
 * <p>字段全部来自 {@code ws_after_sale_action} 及其退款单、补送结果关联；本 Vo 不承载
 * 失败原因、操作人、重试次数或退款原始报文。金额单位为分，水量单位为毫升，页面只负责格式化。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniAfterSaleProgressVo", description = "用户本人订单的售后处理进度")
public class MiniAfterSaleProgressVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "售后号")
    private String afterSaleNo;

    @Schema(description = "售后来源(1370)")
    private Integer sourceType;

    @Schema(description = "售后动作类型(1371)")
    private Integer actionType;

    @Schema(description = "售后执行状态(1372)")
    private Integer actionStatus;

    @Schema(description = "批准的受影响数量(桶)")
    private Integer approvedCount;

    @Schema(description = "水品权益返还金额(分)")
    private Long refundProductFen;

    @Schema(description = "配送费返还金额(分)")
    private Long refundServiceFen;

    @Schema(description = "水品权益返还水量(毫升)")
    private Long refundProductMl;

    @Schema(description = "返还金额合计(分)")
    private Long refundAmount;

    @Schema(description = "退款来源(1373)：1微信 2Refund-Sim；仅机构退款有值")
    private Integer refundSource;

    @Schema(description = "补送子订单号")
    private String resendOrderNo;

    @Schema(description = "补送任务号")
    private String resendTaskNo;

    @Schema(description = "售后动作终态时间")
    private String finishTime;
}

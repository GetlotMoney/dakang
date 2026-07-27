package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 订单追溯·申诉记录行 Vo（E2E-03 包C）。
 * <p>逐行共键核验：申诉的 taskId/orderId/userId 必须与已核验的任务-订单一致；
 * mismatch 行只保留申诉ID与原因，理由/裁决等正向内容全部隐藏。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@Schema(name = "AdminAppealTraceVo", description = "订单追溯申诉记录行")
public class AdminAppealTraceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "申诉ID")
    private Long appealId;

    @Schema(description = "共键核验：ok 一致 / mismatch 数据异常（隐藏正向内容）")
    private String linkStatus;

    @Schema(description = "linkStatus=mismatch 时的原因说明")
    private String linkReason;

    @Schema(description = "申诉状态(1352)")
    private Integer appealStatus;

    @Schema(description = "申诉原因码：QUANTITY/QUALITY/DAMAGE/PLACEMENT/OTHER")
    private String appealReason;

    @Schema(description = "申诉原因名称")
    private String appealReasonLabel;

    @Schema(description = "申诉说明")
    private String appealDesc;

    @Schema(description = "用户实收数量")
    private Integer receivedCount;

    @Schema(description = "裁决时间（未裁决为空）")
    private String handleTime;

    @Schema(description = "裁决结果说明（未裁决为空）")
    private String handleResult;

    @Schema(description = "申诉时间")
    private String createTime;
}

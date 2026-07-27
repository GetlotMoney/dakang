package com.jbk.tool.data.mini.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 小程序配送申诉 Vo（E2E-03 包B；对齐 miniapp order.ts DeliveryAppeal）。
 * <p>裁决只消费结果：decisionSummary=HANDLE_RESULT；状态含 5补送待执行（1352）。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "MiniDeliveryAppealVo", description = "小程序配送申诉")
public class MiniDeliveryAppealVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "申诉ID")
    private Long appealId;

    @Schema(description = "配送订单号（关联 ws_order 派生）")
    private String orderNo;

    @Schema(description = "配送任务号（关联 ws_delivery_task 派生）")
    private String taskNo;

    @Schema(description = "申诉用户ID")
    private Long userId;

    @Schema(description = "申诉状态(1352)：1待处理 2成立待补偿 3不成立驳回 4撤销 5补送待执行")
    private Integer appealStatus;

    @Schema(description = "原因码：QUANTITY/QUALITY/DAMAGE/PLACEMENT/OTHER")
    private String reason;

    @Schema(description = "申诉说明")
    private String description;

    @Schema(description = "用户实收数量")
    private Integer receivedCount;

    @Schema(description = "用户举证（受控媒体键）")
    private List<String> evidenceRefs;

    @Schema(description = "登记时间")
    private String createTime;

    @Schema(description = "裁决结果说明（HANDLE_RESULT；未裁决为空）")
    private String decisionSummary;

    @Schema(description = "配送员举证列表（规则16）")
    private List<MiniCourierEvidenceVo> courierEvidences;

    /** 配送员单次举证（时间由服务端统一逻辑时钟落定）。 */
    @Data
    @Accessors(chain = true)
    @Schema(name = "MiniCourierEvidenceVo", description = "配送员举证")
    public static class MiniCourierEvidenceVo implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "举证说明")
        private String description;

        @Schema(description = "举证受控媒体键列表")
        private List<String> evidenceRefs;

        @Schema(description = "举证时间")
        private String time;
    }
}

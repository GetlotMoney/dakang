package com.jbk.tool.data.delivery.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端配送申诉列表项 Vo（E2E-03 包C：申诉+任务+订单+用户+处理人 join 投影）。
 * <p>派生字段来源：taskNo 关联 ws_delivery_task，orderNo 关联 ws_order，
 * userName 关联 ws_user，handleByName 关联 api_employee。
 * 手机号只出服务端 PhoneMask 脱敏值，原始号 {@code @JsonIgnore} 兜底不出接口。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Schema(name = "AdminDeliveryAppealItemVo", description = "管理端配送申诉列表项")
public class AdminDeliveryAppealItemVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "申诉ID")
    private Long appealId;

    @Schema(description = "配送任务ID")
    private Long taskId;

    @Schema(description = "任务号（关联 ws_delivery_task 派生）")
    private String taskNo;

    @Schema(description = "配送订单ID")
    private Long orderId;

    @Schema(description = "订单号（关联 ws_order 派生）")
    private String orderNo;

    @Schema(description = "申诉用户ID")
    private Long userId;

    @Schema(description = "申诉用户姓名（关联 ws_user 派生）")
    private String userName;

    @Schema(description = "申诉用户脱敏手机号（服务端脱敏）")
    private String userMaskedPhone;

    @Schema(hidden = true)
    @JsonIgnore
    private String userPhoneRaw;

    @Schema(description = "申诉原因码：QUANTITY/QUALITY/DAMAGE/PLACEMENT/OTHER")
    private String appealReason;

    @Schema(description = "申诉原因名称（服务端映射）")
    private String appealReasonLabel;

    @Schema(description = "申诉说明")
    private String appealDesc;

    @Schema(description = "用户实收数量")
    private Integer receivedCount;

    @Schema(description = "申诉状态(1352)：1待处理 2成立待补偿 3不成立驳回 4撤销 5补送待执行")
    private Integer appealStatus;

    @Schema(description = "处理人ID（api_employee.ID）")
    private Long handleBy;

    @Schema(description = "处理人姓名（关联 api_employee 派生）")
    private String handleByName;

    @Schema(description = "处理时间")
    private String handleTime;

    @Schema(description = "处理结果说明")
    private String handleResult;

    @Schema(description = "申诉时间")
    private String createTime;

    // ===== D-215 案件聚合列：只在管理端聚合分页下发；单条查询（详情/往来时间线）为空 =====

    @Schema(description = "该任务累计申诉次数（>1 表示反复申诉，运营需看完整往来）")
    private Integer appealCount;

    @Schema(description = "当前待处理申诉ID；为空表示案件当前没有待裁决申诉。"
            + "唯一键 uk_appeal_active_task 保证一个任务同时至多一条")
    private Long activeAppealId;

    @Schema(description = "首次申诉时间（案件内最早）")
    private String firstAppealTime;

    @Schema(description = "最近申诉时间（案件内最晚，列表按此倒序）")
    private String lastAppealTime;
}

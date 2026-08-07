package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 机主服务申请 Vo（对齐 miniapp device.ts OwnerServiceRequest + E2E-05 扩展 workOrderNo/trace）。
 * 背后是真实 ws_work_order；workOrderNo 即三端贯穿的工单号（S14 共键）。
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniOwnerServiceVo", description = "机主服务申请")
public class MiniOwnerServiceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "申报请求标识（提交时的幂等键）")
    private String requestId;

    @Schema(description = "账号ID（=会话 userId）")
    private Long accountId;

    @Schema(description = "工单号（三端贯穿，PC 运维工单同号）")
    private String workOrderNo;

    @Schema(description = "设备编号")
    private String deviceNo;

    @Schema(description = "服务类型：REPAIR维修 / PART配件")
    private String serviceType;

    @Schema(description = "问题描述")
    private String description;

    @Schema(description = "证据引用（受控 mediaKey 数组，不含路径）")
    private List<String> evidenceRefs;

    @Schema(description = "脱敏联系电话（账号注册手机号脱敏）")
    private String maskedContactPhone;

    @Schema(description = "状态：PENDING_ACCEPTANCE/PROCESSING/COMPLETED/REJECTED")
    private String status;

    @Schema(description = "工单状态(1362 原值)：1待确认 2待分配 3处理中 4待复核 5已关闭 6已驳回")
    private Integer workOrderStatus;

    @Schema(description = "驳回原因（已驳回时返回）")
    private String rejectReason;

    @Schema(description = "处理结果（关闭后返回）")
    private String finishResult;

    @Schema(description = "创建时间")
    private String createTime;

    @Schema(description = "证据模式：external-snapshot=真实受控媒体")
    private String evidenceMode;

    @Schema(description = "处理轨迹（仅详情返回，时间正序）")
    private List<MiniOwnerServiceTraceVo> trace;
}

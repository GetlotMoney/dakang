package com.jbk.tool.data.ops.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 运维工单视图（E2E-05 包C）。证据字段只回受控 mediaKey 数组——
 * 本机路径与存储细节绝不出网（任务书包C安全要求）。
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsWorkOrderVo", description = "运维工单视图")
public class WsWorkOrderVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "工单号，三端贯穿")
    private String orderNo;

    @Schema(description = "工单类型(1365)")
    private Integer workType;

    @Schema(description = "设备ID")
    private Long deviceId;

    @Schema(description = "设备编号")
    private String deviceNo;

    @Schema(description = "所属水站名称")
    private String stationName;

    @Schema(description = "来源：1告警转入 2机主申报 3后台创建")
    private Integer sourceType;

    @Schema(description = "来源告警ID")
    private Long alarmId;

    @Schema(description = "来源告警内容（详情联查）")
    private String alarmContent;

    @Schema(description = "申报人ID（机主申报）")
    private Long applicantUserId;

    @Schema(description = "工单标题")
    private String orderTitle;

    @Schema(description = "问题描述")
    private String orderContent;

    @Schema(description = "申报证据媒体键JSON数组")
    private String orderPhotos;

    @Schema(description = "处理人ID")
    private Long assigneeId;

    @Schema(description = "处理人姓名")
    private String assigneeName;

    @Schema(description = "工单状态(1362)")
    private Integer orderStatus;

    @Schema(description = "分配时间")
    private String assignTime;

    @Schema(description = "处理提交时间")
    private String finishTime;

    @Schema(description = "处理结果")
    private String finishResult;

    @Schema(description = "处理证据媒体键JSON数组")
    private String resultPhotos;

    @Schema(description = "复核人ID")
    private Long reviewBy;

    @Schema(description = "复核时间")
    private String reviewTime;

    @Schema(description = "复核意见")
    private String reviewRemark;

    @Schema(description = "驳回原因")
    private String rejectReason;

    @Schema(description = "关闭时间")
    private String closeTime;

    @Schema(description = "创建时间")
    private String createTime;

    @Schema(description = "状态轨迹（仅详情返回，按时间正序）")
    private List<WorkOrderTraceVo> trace;
}

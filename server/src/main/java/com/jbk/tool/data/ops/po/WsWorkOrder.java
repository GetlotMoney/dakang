package com.jbk.tool.data.ops.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 运维工单 Po（E2E-05 包C）。告警转单/机主申报/PC巡检三来源共用一张表一个状态机；
 * 全部状态迁移经精确前态 CAS + VERSION 递增，影响行数必须为 1。
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_work_order")
@Schema(name = "WsWorkOrder", description = "运维工单表")
public class WsWorkOrder extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @TableField("ORDER_NO")
    @Schema(description = "工单号，三端贯穿（uk_wo_no）")
    private String orderNo;

    @TableField("WORK_TYPE")
    @Schema(description = "工单类型(1365)：1维修 2配件 3巡检")
    private Integer workType;

    @TableField("DEVICE_ID")
    @Schema(description = "设备ID（可空：非设备类工单）")
    private Long deviceId;

    @TableField("SOURCE_TYPE")
    @Schema(description = "来源：1告警转入 2机主申报 3后台创建")
    private Integer sourceType;

    @TableField("ALARM_ID")
    @Schema(description = "来源告警ID；一个告警最多转一个工单（uk_wo_alarm）")
    private Long alarmId;

    @TableField("APPLICANT_USER_ID")
    @Schema(description = "申报人（ws_user.ID）；身份只取 KH_USER 会话")
    private Long applicantUserId;

    @TableField("REQUEST_ID")
    @Schema(description = "机主申报幂等键（uk_wo_request）")
    private String requestId;

    @TableField("ORDER_TITLE")
    @Schema(description = "工单标题(max100)")
    private String orderTitle;

    @TableField("ORDER_CONTENT")
    @Schema(description = "问题描述(max1000)")
    private String orderContent;

    @TableField("ORDER_PHOTOS")
    @Schema(description = "申报证据媒体键JSON数组（受控 mediaKey）")
    private String orderPhotos;

    @TableField("ASSIGNEE_ID")
    @Schema(description = "处理人（api_employee.ID）")
    private Long assigneeId;

    @TableField("ORDER_STATUS")
    @Schema(description = "工单状态(1362)：1待确认 2待分配 3处理中 4待复核 5已关闭 6已驳回")
    private Integer orderStatus;

    @TableField("ASSIGN_TIME")
    @Schema(description = "分配时间")
    private String assignTime;

    @TableField("FINISH_TIME")
    @Schema(description = "处理提交时间（转待复核时回填）")
    private String finishTime;

    @TableField("FINISH_RESULT")
    @Schema(description = "处理结果(max500)")
    private String finishResult;

    @TableField("RESULT_PHOTOS")
    @Schema(description = "处理证据媒体键JSON数组（员工身份登记，OWNER_PORTAL=1）")
    private String resultPhotos;

    @TableField("REVIEW_BY")
    @Schema(description = "复核人（api_employee.ID）")
    private Long reviewBy;

    @TableField("REVIEW_TIME")
    @Schema(description = "复核时间（通过或退回都回填）")
    private String reviewTime;

    @TableField("REVIEW_REMARK")
    @Schema(description = "复核意见(max500)；复核退回时必填")
    private String reviewRemark;

    @TableField("REJECT_REASON")
    @Schema(description = "驳回原因(max500)；待确认→已驳回时必填")
    private String rejectReason;

    @TableField("CLOSE_TIME")
    @Schema(description = "关闭时间（复核通过后回填）")
    private String closeTime;

    @TableField("VERSION")
    @Schema(description = "乐观锁版本；迁移时递增")
    private Integer version;
}

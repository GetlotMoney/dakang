package com.jbk.tool.data.ops.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 运维工单业务对象（PC 端：查询 + 巡检建单 + 状态动作）。
 *
 * <p>动作字段的必填校验在服务端按动作分别做（驳回必填原因、复核退回必填意见），
 * 不用分组注解硬编码——同一 Bo 服务多个动作端点。</p>
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsWorkOrderBo", description = "运维工单业务对象")
public class WsWorkOrderBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "工单信息不为空")
    private Long id;

    // ===== 巡检建单（InsertGroup）=====

    @Schema(description = "工单类型(1365)：后台建单允许 1维修 2配件 3巡检")
    @NotNull(groups = InsertGroup.class, message = "工单类型不为空")
    private Integer workType;

    @Schema(description = "设备ID（设备类工单必填，服务端核验档案）")
    private Long deviceId;

    @Schema(description = "工单标题")
    @NotBlank(groups = InsertGroup.class, message = "工单标题不为空")
    @Size(max = 100, message = "工单标题不能超过100字")
    private String orderTitle;

    @Schema(description = "问题描述")
    @Size(max = 1000, message = "问题描述不能超过1000字")
    private String orderContent;

    // ===== 状态动作参数 =====

    @Schema(description = "处理人（分配动作；必须是有效员工）")
    private Long assigneeId;

    @Schema(description = "驳回原因（驳回动作必填）")
    @Size(max = 500, message = "驳回原因不能超过500字")
    private String rejectReason;

    @Schema(description = "处理结果（提交结果动作必填）")
    @Size(max = 500, message = "处理结果不能超过500字")
    private String finishResult;

    @Schema(description = "处理证据媒体键（员工登记的受控 mediaKey，可空）")
    @Size(max = 9, message = "处理证据最多9张")
    private List<String> resultPhotos;

    @Schema(description = "复核意见（复核退回必填，复核通过可空）")
    @Size(max = 500, message = "复核意见不能超过500字")
    private String reviewRemark;

    // ===== 查询筛选 =====

    @Schema(description = "【筛选】工单号")
    private String orderNo;

    @Schema(description = "【筛选】工单状态(1362)")
    private Integer orderStatus;

    @Schema(description = "【筛选】工单类型(1365)")
    private Integer filterWorkType;

    @Schema(description = "【筛选】来源：1告警转入 2机主申报 3后台创建")
    private Integer sourceType;

    @Schema(description = "【筛选】设备ID")
    private Long filterDeviceId;

    @Schema(description = "【筛选】处理人")
    private Long filterAssigneeId;
}

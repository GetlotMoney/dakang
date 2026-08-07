package com.jbk.tool.data.ops.bo;

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
 * 机主服务申报业务对象（E2E-05 包C/E：维修申请、配件申请）。
 *
 * <p>不含 userId 字段——申报人身份只取 KH_USER 会话（任务书 3.3），前端传了也没有
 * 可赋值的位置。requestId 为幂等键：同键重复提交返回原工单，不产生第二张。</p>
 *
 * @author dakang
 * @since 2026-07-30
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WorkOrderApplyBo", description = "机主服务申报业务对象")
public class WorkOrderApplyBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "申报幂等键（前端生成一次并在重试间保持不变，max64）")
    @NotBlank(message = "申报请求标识不为空")
    @Size(max = 64, message = "申报请求标识过长")
    private String requestId;

    @Schema(description = "目标设备ID（必须在本人名下，服务端校验归属）")
    @NotNull(message = "目标设备不为空")
    private Long deviceId;

    @Schema(description = "工单类型(1365)：机主可申报 1维修 2配件")
    @NotNull(message = "申报类型不为空")
    private Integer workType;

    @Schema(description = "问题描述")
    @NotBlank(message = "问题描述不为空")
    @Size(max = 1000, message = "问题描述不能超过1000字")
    private String orderContent;

    @Schema(description = "申报证据媒体键（机主登记的受控 mediaKey，可空）")
    @Size(max = 9, message = "申报证据最多9张")
    private List<String> orderPhotos;
}

package com.jbk.tool.data.user.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 配送员业务对象（人工创建 + 审核/启停，REQ-079）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsCourierBo", description = "配送员业务对象")
public class WsCourierBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "配送员信息不为空")
    private Long id;

    @Schema(description = "用户ID（ws_user.ID，配送员准入记录关联同一C端用户）")
    @NotNull(groups = InsertGroup.class, message = "关联用户不为空")
    private Long userId;

    @Schema(description = "姓名(max50)")
    @NotEmpty(groups = InsertGroup.class, message = "姓名不为空")
    @Size(groups = InsertGroup.class, max = 50, message = "姓名长度不能超过50")
    private String courierName;

    @Schema(description = "联系电话(max20)")
    @NotEmpty(groups = InsertGroup.class, message = "联系电话不为空")
    @Size(groups = InsertGroup.class, max = 20, message = "联系电话长度不能超过20")
    private String courierPhone;

    @Schema(description = "身份证号(max30)")
    @Size(max = 30, message = "身份证号长度不能超过30")
    private String idCardNo;

    @Schema(description = "服务水站ID集(逗号分隔,max200)，空=未配置并默认拒绝接单")
    @Size(max = 200, message = "服务水站长度不能超过200")
    private String stationIds;

    @Schema(description = "服务区域(max100)")
    @Size(max = 100, message = "服务区域长度不能超过100")
    private String serviceRegion;

    @Schema(description = "状态(1350)：1待审核 2启用 3停用 4审核驳回（筛选用）")
    private Integer courierStatus;

    @Schema(description = "目标状态（audit 专用）：2启用/通过 3停用 4驳回")
    private Integer targetStatus;

    @Schema(description = "审核备注(max500)（audit 专用，驳回/停用时必填）")
    @Size(max = 500, message = "审核备注长度不能超过500")
    private String auditRemark;
}

package com.jbk.tool.data.user.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 配送员响应对象
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsCourierVo", description = "配送员响应对象")
public class WsCourierVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "用户ID（ws_user.ID）")
    private Long userId;

    @Schema(description = "关联用户姓名（关联 ws_user 派生）")
    private String userName;

    @Schema(description = "姓名(max50)")
    private String courierName;

    @Schema(description = "联系电话(max20)")
    private String courierPhone;

    @Schema(description = "身份证号(max30)")
    private String idCardNo;

    @Schema(description = "服务水站ID集(逗号分隔)，空=未配置并默认拒绝接单")
    private String stationIds;

    @Schema(description = "服务水站名称集（关联 ws_station 派生）")
    private String stationNames;

    @Schema(description = "服务区域(max100)")
    private String serviceRegion;

    @Schema(description = "状态(1350)：1待审核 2启用 3停用 4审核驳回")
    private Integer courierStatus;

    @Schema(description = "审核备注(max500)")
    private String auditRemark;
}

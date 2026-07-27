package com.jbk.tool.data.user.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 水卡业务对象
 * <p>
 * 一期口径：后台水卡只读 + 状态管理（冻结/解冻）；开卡/充值/迁移属商业一期
 * （REQ-078 高风险资金操作需财务审核），本 Bo 不承载余额与开卡字段。
 * </p>
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsCardBo", description = "水卡业务对象")
public class WsCardBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "水卡信息不为空")
    private Long id;

    @Schema(description = "卡号(max32)，筛选用模糊匹配")
    @Size(max = 32, message = "卡号长度不能超过32")
    private String cardNo;

    @Schema(description = "卡类型(1331)：1虚拟卡 2实体卡")
    private Integer cardType;

    @Schema(description = "持卡用户ID（用户详情抽屉按用户过滤）")
    private Long userId;

    @Schema(description = "卡状态(1332)：1正常 2冻结 3已过期 4已注销")
    private Integer cardStatus;

    @Schema(description = "目标状态（changeStatus 专用）：1解冻为正常 2冻结")
    private Integer targetStatus;

    @Schema(description = "状态变更原因(max500)（changeStatus 专用，写入备注）")
    @Size(max = 500, message = "变更原因长度不能超过500")
    private String changeReason;
}

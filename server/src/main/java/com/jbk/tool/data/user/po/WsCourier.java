package com.jbk.tool.data.user.po;

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
 * 配送员表 Po（准入状态机：待审核→启用/驳回，启用↔停用，REQ-079）
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_courier")
@Schema(name = "WsCourier", description = "配送员表")
public class WsCourier extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "用户ID（ws_user.ID，配送员准入记录关联同一C端用户）")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "姓名(max50)")
    @TableField("COURIER_NAME")
    private String courierName;

    @Schema(description = "联系电话(max20)")
    @TableField("COURIER_PHONE")
    private String courierPhone;

    @Schema(description = "身份证号(max30)，脱敏展示")
    @TableField("ID_CARD_NO")
    private String idCardNo;

    @Schema(description = "服务水站ID集(逗号分隔,max200)，空=未配置并默认拒绝接单")
    @TableField("STATION_IDS")
    private String stationIds;

    @Schema(description = "服务区域(max100)")
    @TableField("SERVICE_REGION")
    private String serviceRegion;

    @Schema(description = "状态(1350)：1待审核 2启用 3停用 4审核驳回")
    @TableField("COURIER_STATUS")
    private Integer courierStatus;

    @Schema(description = "审核备注(max500)")
    @TableField("AUDIT_REMARK")
    private String auditRemark;
}

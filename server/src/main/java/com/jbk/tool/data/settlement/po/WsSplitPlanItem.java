package com.jbk.tool.data.settlement.po;

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
 * 分润 V2 计划项（E2E-08 S1）。角色/线/层级/模式的合法搭配由
 * {@code SplitPlanSnapshot#validate()} 整版校验，数据库唯一键只兜「同线同角色不重复」。
 *
 * @author dakang
 * @since 2026-08-06
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_split_plan_item")
@Schema(name = "WsSplitPlanItem", description = "分润V2计划项")
public class WsSplitPlanItem extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属计划ID")
    @TableField("PLAN_ID")
    private Long planId;

    @Schema(description = "基数线：WATER_SALE / DELIVERY_FEE")
    @TableField("PRODUCT_LINE")
    private String productLine;

    @Schema(description = "角色编码，见 SplitV2Enum.RoleCode")
    @TableField("ROLE_CODE")
    private String roleCode;

    @Schema(description = "区域层级：NONE / PROVINCE / CITY / COUNTY")
    @TableField("REGION_LEVEL")
    private String regionLevel;

    @Schema(description = "万分比 0..10000；级差模式下为该层累计上限")
    @TableField("RATE_BP")
    private Integer rateBp;

    @Schema(description = "比例模式：FIXED / REGIONAL_CUMULATIVE")
    @TableField("RATE_MODE")
    private String rateMode;
}

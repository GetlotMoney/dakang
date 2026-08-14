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
 * 分润 V2 完整计划头（E2E-08 S1，任务书 5.1）。区别于 V1 各收款方独立选版（偏差 4）：
 * V2 整版发布整版生效，项挂头下（{@link WsSplitPlanItem}），发布前 {@code SplitPlanSnapshot#validate()} 整版校验。
 * 正式比例未获甲方书面确认前，本表不得存在 ACTIVE 行（任务书 5.1）。
 *
 * @author dakang
 * @since 2026-08-06
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_split_plan")
@Schema(name = "WsSplitPlan", description = "分润V2完整计划头")
public class WsSplitPlan extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "计划版本号，业务唯一(max20)")
    @TableField("PLAN_VERSION")
    private String planVersion;

    @Schema(description = "生效时间yyyyMMddHHmmss")
    @TableField("EFFECT_TIME")
    private String effectTime;

    @Schema(description = "计划状态：1草稿 2生效 3停用")
    @TableField("PLAN_STATUS")
    private Integer planStatus;

    @Schema(description = "备注(max200)")
    @TableField("PLAN_REMARK")
    private String planRemark;
}

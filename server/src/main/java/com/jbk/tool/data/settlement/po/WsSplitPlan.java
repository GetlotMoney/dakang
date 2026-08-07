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
 * 分润 V2 完整计划头（E2E-08 S1，任务书 5.1）。
 *
 * <p>与 V1 {@code ws_split_config} 的本质区别：V1 各收款方独立选生效版本，可能把
 * 不同时间发布的比例拼成一张没人审过的计划（偏差 4）；V2 计划整版发布、整版生效，
 * 项挂在头下（{@link WsSplitPlanItem}），发布前由
 * {@code SplitPlanSnapshot#validate()} 整版校验。</p>
 *
 * <p>正式比例未获甲方书面确认前，本表不得存在 ACTIVE 行（任务书 5.1）——
 * 会议中的 50%/5%/10-8-5 全部是讨论示例。</p>
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

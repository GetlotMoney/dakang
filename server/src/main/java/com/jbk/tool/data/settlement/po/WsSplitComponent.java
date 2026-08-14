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
 * 分润 V2 组件证据（E2E-08 S1，任务书 5.4）。计算证据而非付款状态机（付款推进仍由 ws_split_record 承担），
 * 不设状态列，一经写入不再变更。
 * {@code COMPONENT_KEY} 库内唯一：同订单同基数线同角色恒一条；多角色 key 因角色不同不会被合并（矩阵 12）。
 *
 * @author dakang
 * @since 2026-08-06
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_split_component")
@Schema(name = "WsSplitComponent", description = "分润V2组件证据")
public class WsSplitComponent extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "订单ID")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "订单号(max40)")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "基数线：WATER_SALE / DELIVERY_FEE")
    @TableField("PRODUCT_LINE")
    private String productLine;

    @Schema(description = "该线权威基数金额（整数分）")
    @TableField("BASIS_AMOUNT")
    private Long basisAmount;

    @Schema(description = "角色编码，见 SplitV2Enum.RoleCode")
    @TableField("ROLE_CODE")
    private String roleCode;

    @Schema(description = "收益人用户ID；平台余数行为 0 哨兵")
    @TableField("RECEIVER_USER_ID")
    private Long receiverUserId;

    @Schema(description = "实际生效万分比；级差角色为级差后实得；平台余数行为 -1")
    @TableField("EFFECTIVE_RATE")
    private Integer effectiveRate;

    @Schema(description = "分得金额（整数分）")
    @TableField("SPLIT_AMOUNT")
    private Long splitAmount;

    @Schema(description = "计划版本号(max20)")
    @TableField("PLAN_VERSION")
    private String planVersion;

    @Schema(description = "归属来源：PRIVATE_REFERRAL / PUBLIC_UNASSIGNED / PUBLIC_MANUAL")
    @TableField("ATTRIBUTION_SOURCE")
    private String attributionSource;

    @Schema(description = "幂等键 SPLITV2:订单号:线:角色，数据库唯一(max120)")
    @TableField("COMPONENT_KEY")
    private String componentKey;
}

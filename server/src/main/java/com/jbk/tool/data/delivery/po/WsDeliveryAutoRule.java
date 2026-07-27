package com.jbk.tool.data.delivery.po;

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
 * 自动补货规则表 Po（REQ-011：用户显式配置固定周期，非 AI 预测）。
 * <p>第 n 期订单号由「userId + 规则ID + 期序」确定性派生，同期重复生成撞
 * uk_order_no 幂等（E2E-03 规则19「只生成一次」的数据库层保证）。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_delivery_auto_rule")
@Schema(name = "WsDeliveryAutoRule", description = "自动补货规则表")
public class WsDeliveryAutoRule extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "规则创建幂等键：sha256(userId:requestId)")
    @TableField("RULE_KEY")
    private String ruleKey;

    @Schema(description = "规则所属用户ID")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "扣款水卡ID（逐期创单按当期状态重新校验）")
    @TableField("CARD_ID")
    private Long cardId;

    @Schema(description = "配送水站ID")
    @TableField("STATION_ID")
    private Long stationId;

    @Schema(description = "水种ID")
    @TableField("WATER_TYPE_ID")
    private Long waterTypeId;

    @Schema(description = "容器规格(max20)")
    @TableField("CONTAINER_SPEC")
    private String containerSpec;

    @Schema(description = "每期配送数量")
    @TableField("DELIVERY_COUNT")
    private Integer deliveryCount;

    @Schema(description = "每期计划回收数量")
    @TableField("PLAN_RETURN_COUNT")
    private Integer planReturnCount;

    @Schema(description = "收水地址(max200)")
    @TableField("RECEIVE_ADDRESS")
    private String receiveAddress;

    @Schema(description = "收货电话(max20)")
    @TableField("RECEIVE_PHONE")
    private String receivePhone;

    @Schema(description = "固定周期天数(3~90)")
    @TableField("INTERVAL_DAYS")
    private Integer intervalDays;

    @Schema(description = "周期锚点=规则创建时间；第n期到期=锚点+n*周期天")
    @TableField("ANCHOR_TIME")
    private String anchorTime;

    @Schema(description = "规则状态(1355)：1启用 2停用")
    @TableField("RULE_STATUS")
    private Integer ruleStatus;
}

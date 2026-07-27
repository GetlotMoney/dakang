package com.jbk.tool.data.trade.po;

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
 * 钱包流水表 Po（水卡余额/水量每次增减一条；只插入不更新；对账以此为准）。
 * <p>资金铁律：每次原子扣减/入账必须同事务追加一条流水，AMOUNT_AFTER/ML_AFTER 记变动后快照。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_wallet_flow")
@Schema(name = "WsWalletFlow", description = "钱包流水表")
public class WsWalletFlow extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "水卡ID")
    @TableField("CARD_ID")
    private Long cardId;

    @Schema(description = "操作用户ID（成员用卡时为成员ID，审计归属）")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "流水类型(1344)：1充值入账 2取水扣减 3退款返还 4补偿入账 5后台调整 6过期清零")
    @TableField("FLOW_TYPE")
    private Integer flowType;

    @Schema(description = "余额变动(分)，正入负出")
    @TableField("AMOUNT_CHANGE")
    private Long amountChange;

    @Schema(description = "水量变动(毫升)，正入负出")
    @TableField("ML_CHANGE")
    private Long mlChange;

    @Schema(description = "变动后余额(分)快照")
    @TableField("AMOUNT_AFTER")
    private Long amountAfter;

    @Schema(description = "变动后水量(毫升)快照")
    @TableField("ML_AFTER")
    private Long mlAfter;

    @Schema(description = "关联订单ID")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "备注(max500)")
    @TableField("FLOW_REMARK")
    private String flowRemark;

    @Schema(description = "业务幂等键；充值固定 RECHARGE:<orderNo>")
    @TableField("BIZ_IDEMPOTENCY_KEY")
    private String bizIdempotencyKey;
}

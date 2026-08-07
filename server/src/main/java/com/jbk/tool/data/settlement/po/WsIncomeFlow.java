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
 * 收益流水 Po（E2E-08 / REQ-072）：只插不更新；禁止与 ws_wallet_flow 混表。
 * 幂等键命名空间（已登记，防 varchar64 截断碰撞）：INCOME:&lt;splitId&gt; / WITHDRAW:&lt;申请号&gt;。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_income_flow")
@Schema(name = "WsIncomeFlow", description = "收益流水")
public class WsIncomeFlow extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "收益人；按会话强制过滤（铁律6）")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "收益流水类型(1378)：1分润入账 2分润回退(预留) 3提现冻结 4提现完成(预留) 5提现驳回解冻")
    @TableField("FLOW_TYPE")
    private Integer flowType;

    @Schema(description = "变动金额(分)，入账为正、出账为负")
    @TableField("AMOUNT_FEN")
    private Long amountFen;

    @Schema(description = "变动后可用余额(分)；逐笔连续为对账不变式")
    @TableField("AFTER_FEN")
    private Long afterFen;

    @Schema(description = "来源分账记录（1/2 类必填）")
    @TableField("SPLIT_ID")
    private Long splitId;

    @Schema(description = "原始订单号快照")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "幂等键")
    @TableField("BIZ_IDEMPOTENCY_KEY")
    private String bizIdempotencyKey;

    @Schema(description = "备注")
    @TableField("FLOW_REMARK")
    private String flowRemark;
}

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
 * 日对账差异台账 Po（E2E-08 包C）。每轮重算整批替换（以最新一轮为准）。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_reconcile_diff")
@Schema(name = "WsReconcileDiff", description = "日对账差异台账")
public class WsReconcileDiff extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属对账批")
    @TableField("TASK_ID")
    private Long taskId;

    @Schema(description = "账期日")
    @TableField("BIZ_DATE")
    private String bizDate;

    @Schema(description = "差异分类(1379)：1单边账 2金额不符 3状态不符 4账本断裂")
    @TableField("DIFF_TYPE")
    private Integer diffType;

    @Schema(description = "核对维度：payment-fact/order-flow/card-ledger/split-sum/income-ledger")
    @TableField("CHECK_DIMENSION")
    private String checkDimension;

    @Schema(description = "差异主体业务键（订单号/卡ID/账户ID）")
    @TableField("BIZ_KEY")
    private String bizKey;

    @Schema(description = "期望值")
    @TableField("EXPECTED_VAL")
    private String expectedVal;

    @Schema(description = "实际值")
    @TableField("ACTUAL_VAL")
    private String actualVal;

    @Schema(description = "备注")
    @TableField("DIFF_REMARK")
    private String diffRemark;
}

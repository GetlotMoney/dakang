package com.jbk.tool.data.identity.po;

import com.baomidou.mybatisplus.annotation.*;
import com.jbk.tool.data.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("ws_withdraw_order")
public class WsWithdrawOrder extends BaseEntity {
    @TableId(value = "ID", type = IdType.AUTO) private Long id;
    @TableField("WITHDRAW_NO") private String withdrawNo;
    @TableField("REQUEST_ID") private String requestId;
    @TableField("USER_ID") private Long userId;
    @TableField("AMOUNT_FEN") private Long amountFen;
    @TableField("WITHDRAW_STATUS") private Integer withdrawStatus;
    @TableField("PAYOUT_SOURCE") private Integer payoutSource;
    @TableField("APPLIED_TIME") private String appliedTime;
    @TableField("FINISHED_TIME") private String finishedTime;
    @TableField("FAIL_REASON") private String failReason;
}

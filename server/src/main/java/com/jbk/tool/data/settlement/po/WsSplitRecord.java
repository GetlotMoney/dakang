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
 * 分账记录 Po（表由 E2E-02 期预建，E2E-08 首次启用）。
 * 幂等：uk_split_order_receiver(ORDER_ID,RECEIVER_TYPE,RECEIVER_USER_ID) 库层保证
 * 同单同收款方恒一行；金额=分；SPLIT_RATE_SNAP 为生成时点的配置快照，规则变更不影响历史。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_split_record")
@Schema(name = "WsSplitRecord", description = "分账记录")
public class WsSplitRecord extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "订单ID（追溯锚；SPLIT_REMARK 恒存订单号快照供列表直读与收益流水透传）")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "收款方类型(1377)")
    @TableField("RECEIVER_TYPE")
    private Integer receiverType;

    @Schema(description = "收款方用户ID（平台行恒 0 哨兵——NULL 不参与唯一约束会破幂等）")
    @TableField("RECEIVER_USER_ID")
    private Long receiverUserId;

    @Schema(description = "分账金额(分)")
    @TableField("SPLIT_AMOUNT")
    private Long splitAmount;

    @Schema(description = "分账比例快照（万分比）")
    @TableField("SPLIT_RATE_SNAP")
    private String splitRateSnap;

    @Schema(description = "分账状态(1345)：1待分账 2已分账 3分账失败 4已回退")
    @TableField("SPLIT_STATUS")
    private Integer splitStatus;

    @Schema(description = "微信分账单号（真实微信分账接入后使用，Pay-Sim 环境恒 NULL）")
    @TableField("WX_SPLIT_NO")
    private String wxSplitNo;

    @Schema(description = "分账完成时间")
    @TableField("SPLIT_TIME")
    private String splitTime;

    @Schema(description = "订单号快照（enqueue 恒写 orderNo，是收益流水 ORDER_NO 的来源；不作自由备注位）")
    @TableField("SPLIT_REMARK")
    private String splitRemark;

    @Schema(description = "触发回退的退款单ID（扩展位：退款冲减分润经甲方确认后启用）")
    @TableField("REFUND_ID")
    private Long refundId;
}

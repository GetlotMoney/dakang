package com.jbk.tool.data.mall.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商城售后主单 Po（E2E-09 S4）。
 *
 * <p>REFUND_AMOUNT_FEN 由服务端按原订单不可变明细算出：页面与审核人都不得提交这个值。
 * 一旦允许人工输入退款额，退多少就取决于谁在操作，而不是当初卖了多少。</p>
 *
 * @author dakang
 * @since 2026-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_after_sale")
public class WsMallAfterSale extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "售后单号")
    @TableField("AFTER_SALE_NO")
    private String afterSaleNo;

    @Schema(description = "申请用户ID")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "客户端请求号")
    @TableField("REQUEST_ID")
    private String requestId;

    @Schema(description = "原商城订单ID")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "原商城订单号快照")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "原履约仓ID快照")
    @TableField("WAREHOUSE_ID")
    private Long warehouseId;

    @Schema(description = "售后类型(1400)")
    @TableField("AFTER_SALE_TYPE")
    private Integer afterSaleType;

    @Schema(description = "售后状态(1399)")
    @TableField("AFTER_SALE_STATUS")
    private Integer afterSaleStatus;

    @Schema(description = "乐观锁版本")
    @TableField("VERSION")
    private Integer version;

    @Schema(description = "申请原因")
    @TableField("APPLY_REASON")
    private String applyReason;

    @Schema(description = "应退商品金额(分)：服务端算，页面不得提交")
    @TableField("REFUND_AMOUNT_FEN")
    private Long refundAmountFen;

    @Schema(description = "申请时间")
    @TableField("APPLY_TIME")
    private String applyTime;

    @Schema(description = "审核人")
    @TableField("AUDIT_BY")
    private Long auditBy;

    @Schema(description = "审核时间")
    @TableField("AUDIT_TIME")
    private String auditTime;

    @Schema(description = "审核备注")
    @TableField("AUDIT_REMARK")
    private String auditRemark;

    @Schema(description = "确认收货人")
    @TableField("RECEIVE_BY")
    private Long receiveBy;

    @Schema(description = "确认收到退货时间")
    @TableField("RECEIVE_TIME")
    private String receiveTime;

    @Schema(description = "质检人")
    @TableField("INSPECT_BY")
    private Long inspectBy;

    @Schema(description = "质检时间")
    @TableField("INSPECT_TIME")
    private String inspectTime;

    @Schema(description = "质检结论(1401)")
    @TableField("INSPECT_RESULT")
    private Integer inspectResult;

    @Schema(description = "质检说明")
    @TableField("INSPECT_REMARK")
    private String inspectRemark;

    @Schema(description = "完成时间")
    @TableField("FINISH_TIME")
    private String finishTime;

    @Schema(description = "驳回原因")
    @TableField("REJECT_REASON")
    private String rejectReason;
}

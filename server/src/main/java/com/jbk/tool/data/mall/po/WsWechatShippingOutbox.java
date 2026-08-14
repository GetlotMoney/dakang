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

import java.io.Serializable;

/** 微信发货管理同步出站队列（WX-ECO S4）。事实源是 ws_mall_shipment，本表不复制包裹状态。 */
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("ws_wechat_shipping_outbox")
public class WsWechatShippingOutbox extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "商城订单ID")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "商城订单号(out_trade_no)")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "包裹ID（ws_mall_shipment.ID）")
    @TableField("SHIPMENT_ID")
    private Long shipmentId;

    @Schema(description = "微信支付交易号（定位共键）")
    @TableField("TRANSACTION_ID")
    private String transactionId;

    @Schema(description = "付款人用户ID；openid 由 Worker 现查，不落库")
    @TableField("RECEIVER_USER_ID")
    private Long receiverUserId;

    @Schema(description = "幂等键 WXSHIP:<orderNo>:<direction>:<seq>")
    @TableField("BIZ_SYNC_KEY")
    private String bizSyncKey;

    @Schema(description = "微信物流模式：1快递 2同城 3虚拟 4自提")
    @TableField("LOGISTICS_TYPE")
    private Integer logisticsType;

    @Schema(description = "承运商编码（快递模式必填）")
    @TableField("PROVIDER_CODE")
    private String providerCode;

    @Schema(description = "运单号（快递模式必填）")
    @TableField("WAYBILL_NO")
    private String waybillNo;

    @Schema(description = "商品描述（微信侧用户可见）")
    @TableField("ITEM_DESC")
    private String itemDesc;

    @Schema(description = "同步报文快照（业务事务内冻结）")
    @TableField("PAYLOAD_SNAP")
    private String payloadSnap;

    @Schema(description = "处理状态(1408)：1待处理 2处理中 3已处理 4待重试 5需人工")
    @TableField("PROCESSING_STATUS")
    private Integer processingStatus;

    @Schema(description = "已重试次数")
    @TableField("RETRY_COUNT")
    private Integer retryCount;

    @Schema(description = "下次可重试时间")
    @TableField("NEXT_RETRY_TIME")
    private String nextRetryTime;

    @Schema(description = "认领时间")
    @TableField("CLAIM_TIME")
    private String claimTime;

    @Schema(description = "租约到期")
    @TableField("LEASE_UNTIL")
    private String leaseUntil;

    @Schema(description = "未同步原因：NOT_WECHAT_PAY / CLIENT_UNCONFIGURED")
    @TableField("SKIP_REASON")
    private String skipReason;

    @Schema(description = "最近一次失败原因")
    @TableField("LAST_ERROR")
    private String lastError;
}

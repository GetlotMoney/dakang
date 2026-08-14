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
 * 商城物流事件收件箱 Po（E2E-09 L1）。
 *
 * <p>与支付/退款事实同形：外部事实先落库留证，推进交给独立事务。回调恒按
 * 「先验签、再去重、再落事实、最后才推进状态」的顺序——顺序反过来，
 * 一条伪造报文就能在被识破之前先把状态推走。</p>
 *
 * @author dakang
 * @since 2026-08-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_logistics_event")
public class WsMallLogisticsEvent extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "承运商编码")
    @TableField("PROVIDER_CODE")
    private String providerCode;

    @Schema(description = "事实渠道：1回调推送 2主动查询 3内部模拟")
    @TableField("FACT_CHANNEL")
    private Integer factChannel;

    @Schema(description = "承运方事件唯一键：同键即同一条事实")
    @TableField("PROVIDER_EVENT_KEY")
    private String providerEventKey;

    @Schema(description = "归属包裹ID；运单号对不上任何包裹时为空并转人工")
    @TableField("SHIPMENT_ID")
    private Long shipmentId;

    @Schema(description = "运单号：事实自带，与包裹核对")
    @TableField("WAYBILL_NO")
    private String waybillNo;

    @Schema(description = "事件状态(1407)：白名单外留证转人工")
    @TableField("EVENT_STATE")
    private String eventState;

    @Schema(description = "承运方事件发生时间（14位业务时间，严格校验）")
    @TableField("EVENT_TIME")
    private String eventTime;

    @Schema(description = "事件描述：承运方文案，平台只展示不解析")
    @TableField("EVENT_DESC")
    private String eventDesc;

    @Schema(description = "原始报文：留证用，不下发任何前端")
    @TableField("RAW_BODY")
    private String rawBody;

    @Schema(description = "原始报文摘要：同键重放的正文一致性判据")
    @TableField("RAW_BODY_SHA256")
    private String rawBodySha256;

    @Schema(description = "验签方式：1内部模拟签名 2承运方签名")
    @TableField("VERIFY_METHOD")
    private Integer verifyMethod;

    @Schema(description = "处理状态(1408)")
    @TableField("PROCESSING_STATUS")
    private Integer processingStatus;

    @Schema(description = "重试次数：到上限转人工")
    @TableField("RETRY_COUNT")
    private Integer retryCount;

    @Schema(description = "事实接收时间")
    @TableField("RECEIVED_TIME")
    private String receivedTime;

    @Schema(description = "处理完成时间")
    @TableField("PROCESSED_TIME")
    private String processedTime;

    @Schema(description = "认领时间")
    @TableField("CLAIM_TIME")
    private String claimTime;

    @Schema(description = "租约到期：过期后可被重新认领，防止崩溃后卡死")
    @TableField("LEASE_UNTIL")
    private String leaseUntil;

    @Schema(description = "最近一次失败原因")
    @TableField("LAST_ERROR")
    private String lastError;
}

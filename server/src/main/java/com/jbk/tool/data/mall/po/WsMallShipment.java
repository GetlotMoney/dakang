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
 * 商城出库包裹 Po（E2E-09 L1 多渠道物流）。一个履约总单可挂多包裹；一期只产生一个正向包裹（多包裹先建模不实现，防日后改唯一键）。
 * 厂商差异只落 PROVIDER_CODE/SERVICE_CODE/PROVIDER_ORDER_NO 通用列与适配器，不给履约总单堆厂商专用字段。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_shipment")
public class WsMallShipment extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "所属履约总单ID")
    @TableField("FULFILL_ID")
    private Long fulfillId;

    @Schema(description = "商城订单ID")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "商城订单号：三端共用的唯一业务号")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "来源售后单ID：换货补发/退货包裹的来源，正向发货恒 null")
    @TableField("SOURCE_AFTER_SALE_ID")
    private Long sourceAfterSaleId;

    @Schema(description = "包裹方向(1405)：1正向发货 2退货 3换货补发")
    @TableField("DIRECTION")
    private Integer direction;

    @Schema(description = "同方向内包裹序号，从1起；一期恒为1")
    @TableField("SHIPMENT_SEQ")
    private Integer shipmentSeq;

    @Schema(description = "承运渠道(1404)：1自营配送 2第三方物流")
    @TableField("FULFILL_MODE")
    private Integer fulfillMode;

    @Schema(description = "承运商编码：自营恒 SELF")
    @TableField("PROVIDER_CODE")
    private String providerCode;

    @Schema(description = "服务类型编码：由适配器定义，平台不解释")
    @TableField("SERVICE_CODE")
    private String serviceCode;

    @Schema(description = "承运方订单号：适配器返回")
    @TableField("PROVIDER_ORDER_NO")
    private String providerOrderNo;

    @Schema(description = "运单号：适配器返回，未取得前为空")
    @TableField("WAYBILL_NO")
    private String waybillNo;

    @Schema(description = "包裹状态(1406)")
    @TableField("SHIPMENT_STATUS")
    private Integer shipmentStatus;

    @Schema(description = "乐观锁版本：状态推进恒用「精确前态+版本」CAS")
    @TableField("VERSION")
    private Integer version;

    @Schema(description = "包裹创建时间（业务时间）")
    @TableField("CREATE_SHIP_TIME")
    private String createShipTime;

    @Schema(description = "揽收时间")
    @TableField("PICKUP_TIME")
    private String pickupTime;

    @Schema(description = "送达时间")
    @TableField("DELIVER_TIME")
    private String deliverTime;

    @Schema(description = "取消时间")
    @TableField("CANCEL_TIME")
    private String cancelTime;

    @Schema(description = "包裹幂等键：MSHIP:<orderNo>:<direction>:<seq>")
    @TableField("BIZ_IDEMPOTENCY_KEY")
    private String bizIdempotencyKey;
}

package com.jbk.tool.data.mall.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 商城履约任务 Vo（E2E-09 S3）。
 *
 * <p>收货电话恒脱敏：只有被分配到本单的配送员才需要联系方式，而联系方式属于
 * 用户隐私，展示出口一律不给原文。</p>
 *
 * @author dakang
 * @since 2026-08-09
 */
@Data
@Accessors(chain = true)
public class MallFulfillVo {

    @Schema(description = "商城订单号：三端共用同一编号")
    private String orderNo;

    @Schema(description = "履约状态(1396)")
    private Integer fulfillStatus;

    @Schema(description = "履约状态名称")
    private String fulfillStatusName;

    @Schema(description = "承运渠道(1400)：0 未定/1 自营配送/2 第三方物流")
    private Integer fulfillMode;

    @Schema(description = "承运渠道名称")
    private String fulfillModeName;

    @Schema(description = "前置仓ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long warehouseId;

    @Schema(description = "前置仓名称")
    private String warehouseName;

    @Schema(description = "配送员ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long courierId;

    @Schema(description = "配送员姓名")
    private String courierName;

    @Schema(description = "配送员电话（脱敏）")
    private String courierPhone;

    @Schema(description = "收货人")
    private String receiverName;

    @Schema(description = "收货电话（脱敏）")
    private String receiverPhone;

    @Schema(description = "收货区域")
    private String receiverRegion;

    @Schema(description = "收货详细地址")
    private String receiverAddress;

    @Schema(description = "拣货时间")
    private String pickTime;

    @Schema(description = "打包时间")
    private String packTime;

    @Schema(description = "分配时间")
    private String assignTime;

    @Schema(description = "取货时间")
    private String fetchTime;

    @Schema(description = "送达时间")
    private String arriveTime;

    @Schema(description = "签收时间")
    private String signTime;

    @Schema(description = "签收方式(1398)")
    private Integer signMethod;

    @Schema(description = "签收备注")
    private String signRemark;

    @Schema(description = "是否换货补发单：由订单的来源售后单推出，配送端据此提示不再收款")
    private Boolean exchangeReshipment;

    @Schema(description = "履约时间线")
    private List<MallFulfillTraceVo> timeline;
}

package com.jbk.tool.data.delivery.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 订单追溯·配送区块 Vo（E2E-03 包C：AdminOrderTraceVo.delivery 由 Mock/空改真实聚合）。
 * <p>两层独立核验：
 * ① linkStatus（履约链）＝任务存在 + 共键一致 + 状态时间矩阵合法 + 总额恒等式；
 * mismatch 时仅保留任务标识与原因，配送员/水种/费用/时间线/三照/消息全部隐藏。
 * ② payment.flowStatus（资金链）＝DELIVERY:订单号 扣款流水核验；两链互不拼凑。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Schema(name = "AdminDeliveryTraceVo", description = "订单追溯配送区块")
public class AdminDeliveryTraceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "履约链核验：ok 一致 / mismatch 数据异常（隐藏正向证据）")
    private String linkStatus;

    @Schema(description = "linkStatus=mismatch 时的原因说明")
    private String linkReason;

    @Schema(description = "任务ID（任务缺失时为空）")
    private Long taskId;

    @Schema(description = "任务号（任务缺失时为空）")
    private String taskNo;

    @Schema(description = "任务状态(1351)")
    private Integer taskStatus;

    @Schema(description = "下单用户姓名（关联 ws_user 派生）")
    private String userName;

    @Schema(description = "下单用户脱敏手机号（服务端脱敏）")
    private String userMaskedPhone;

    @Schema(description = "配送员姓名（关联 ws_courier 派生；待接单为空）")
    private String courierName;

    @Schema(description = "配送员脱敏电话（服务端脱敏）")
    private String courierMaskedPhone;

    @Schema(description = "水站名称")
    private String stationName;

    @Schema(description = "水种名称快照")
    private String waterTypeName;

    @Schema(description = "容器规格")
    private String containerSpec;

    @Schema(description = "计划配送数量")
    private Integer deliveryCount;

    @Schema(description = "实际签收数量")
    private Integer actualDeliveryCount;

    @Schema(description = "计划回收空桶数量")
    private Integer planReturnCount;

    @Schema(description = "实际回收数量")
    private Integer actualReturnCount;

    @Schema(description = "水费快照(分)，D-214 口径：本单实际应扣水费（payWay=3 恒 0）")
    private Long waterAmountFen;

    @Schema(description = "配送费快照(分)")
    private Long deliveryFeeFen;

    @Schema(description = "总额(分)=水费+配送费，即 ORDER_AMOUNT 应扣金额")
    private Long totalAmountFen;

    @Schema(description = "支付方式(1346)：2水卡余额 3水卡水量+余额付配送费（ws_order 派生）")
    private Integer payWay;

    @Schema(description = "payWay=3 的水量抵扣(毫升)，来源创单冻结快照 waterMl；余额支付为空")
    private Long deductWaterMl;

    @Schema(description = "收水地址")
    private String receiveAddress;

    @Schema(description = "收货脱敏电话（服务端脱敏）")
    private String receiveMaskedPhone;

    @Schema(description = "预约配送时间（空=即时单）")
    private String scheduledTime;

    @Schema(description = "申诉截止时间（签收+24h）")
    private String appealDeadline;

    @Schema(description = "签收定位记录状态(1353)")
    private Integer locationStatus;

    @Schema(description = "履约时间线")
    private List<AdminDeliveryTimelineNodeVo> timeline;

    @Schema(description = "签收三照元数据")
    private List<AdminSignPhotoMetaVo> signPhotos;

    @Schema(description = "卡扣款流水核验（资金链，独立于履约链）")
    private AdminDeliveryPaymentVo payment;

    @Schema(description = "配送站内消息证据")
    private List<AdminDeliveryNotificationVo> notifications;
}

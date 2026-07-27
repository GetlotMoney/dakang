package com.jbk.tool.data.delivery.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端配送任务列表项 Vo（E2E-03 包C：订单+任务 join 投影）。
 * <p>派生字段来源：orderNo/orderStatus 关联 ws_order，userName 关联 ws_user，
 * courierName 关联 ws_courier，stationName 关联 ws_station。
 * 三个手机号只出服务端 PhoneMask 脱敏值；SQL 取出的原始号仅作脱敏输入并以
 * {@code @JsonIgnore} 兜底，任何路径都不把明文序列化出接口。
 * Long 经全局 Jackson 序列化为字符串，前端按 string 归一化。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Schema(name = "AdminDeliveryTaskItemVo", description = "管理端配送任务列表项")
public class AdminDeliveryTaskItemVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "任务号")
    private String taskNo;

    @Schema(description = "配送订单ID")
    private Long orderId;

    @Schema(description = "配送订单号（关联 ws_order 派生）")
    private String orderNo;

    @Schema(description = "订单状态(1341)（关联 ws_order 派生）")
    private Integer orderStatus;

    @Schema(description = "支付方式(1346)（关联 ws_order 派生，D-214）：2水卡余额 3水卡水量+余额付配送费")
    private Integer payWay;

    @Schema(description = "下单用户ID")
    private Long userId;

    @Schema(description = "下单用户姓名（关联 ws_user 派生）")
    private String userName;

    @Schema(description = "下单用户脱敏手机号（服务端脱敏；号码异常为空串）")
    private String userMaskedPhone;

    @Schema(hidden = true)
    @JsonIgnore
    private String userPhoneRaw;

    @Schema(description = "收货脱敏电话（任务快照，服务端脱敏）")
    private String receiveMaskedPhone;

    @Schema(hidden = true)
    @JsonIgnore
    private String receivePhoneRaw;

    @Schema(description = "配送员ID（待接单为空）")
    private Long courierId;

    @Schema(description = "配送员姓名（关联 ws_courier 派生）")
    private String courierName;

    @Schema(description = "配送员脱敏电话（服务端脱敏）")
    private String courierMaskedPhone;

    @Schema(hidden = true)
    @JsonIgnore
    private String courierPhoneRaw;

    @Schema(description = "水站ID")
    private Long stationId;

    @Schema(description = "水站名称（关联 ws_station 派生）")
    private String stationName;

    @Schema(description = "水种ID")
    private Long waterTypeId;

    @Schema(description = "水种名称快照")
    private String waterTypeName;

    @Schema(description = "容器规格")
    private String containerSpec;

    @Schema(description = "计划配送数量")
    private Integer deliveryCount;

    @Schema(description = "实际签收数量（未签收为空）")
    private Integer actualDeliveryCount;

    @Schema(description = "计划回收空桶数量")
    private Integer planReturnCount;

    @Schema(description = "实际回收数量（未签收为空）")
    private Integer actualReturnCount;

    @Schema(description = "水费快照(分)")
    private Long waterAmountFen;

    @Schema(description = "配送费快照(分)")
    private Long deliveryFeeFen;

    @Schema(description = "总额(分)=水费+配送费（服务端求和，只核验不重算价目）")
    private Long totalAmountFen;

    @Schema(description = "收水地址")
    private String receiveAddress;

    @Schema(description = "任务状态(1351)")
    private Integer taskStatus;

    @Schema(description = "预约配送时间（空=即时单）")
    private String scheduledTime;

    @Schema(description = "接单时间")
    private String acceptTime;

    @Schema(description = "离站时间")
    private String departTime;

    @Schema(description = "送达时间")
    private String arriveTime;

    @Schema(description = "签收时间")
    private String signTime;

    @Schema(description = "申诉截止时间（签收+24h）")
    private String appealDeadline;

    @Schema(description = "签收定位记录状态(1353)：1已记录 2未记录，签收前为空")
    private Integer locationStatus;

    @Schema(description = "任务创建（下单）时间")
    private String createTime;
}

package com.jbk.tool.data.mini.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 小程序配送任务 Vo（E2E-03 包B；字段严格对齐 miniapp delivery.ts DeliveryTask，保证小程序零改）。
 * <p>Long 由全局 Jackson 序列化为字符串；金额分；时间 yyyyMMddHHmmss。
 * 收货电话只回 {@link com.jbk.tool.utils.PhoneMask} 脱敏值，明文绝不出网（脱敏唯一实现）。</p>
 *
 * @author dakang
 * @since 2026-07-24
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "MiniDeliveryTaskVo", description = "小程序配送任务")
public class MiniDeliveryTaskVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "任务号")
    private String taskNo;

    @Schema(description = "配送订单ID")
    private Long orderId;

    @Schema(description = "配送订单号")
    private String orderNo;

    @Schema(description = "下单用户ID")
    private Long userId;

    @Schema(description = "接单配送员ID（待接单为空）")
    private Long courierId;

    @Schema(description = "配送水站ID")
    private Long stationId;

    @Schema(description = "水站名称（关联 ws_station 派生）")
    private String stationName;

    @Schema(description = "水种ID")
    private Long waterTypeId;

    @Schema(description = "水种名称快照")
    private String waterTypeName;

    @Schema(description = "容器规格：3L袋/5L桶/10L桶/20L桶")
    private String containerSpec;

    @Schema(description = "计划配送数量")
    private Integer plannedDeliveryCount;

    @Schema(description = "实际配送数量（签收时落库）")
    private Integer actualDeliveryCount;

    @Schema(description = "计划回收空桶数量")
    private Integer plannedReturnCount;

    @Schema(description = "实际回收数量（签收时落库）")
    private Integer actualReturnCount;

    @Schema(description = "收水地址")
    private String receiveAddress;

    @Schema(description = "收货电话（PhoneMask 脱敏，明文不出网）")
    private String maskedPhone;

    @Schema(description = "价格快照：水费+配送费，总额=两者之和（规则2；D-214 口径水费为实际应扣金额）")
    private MiniDeliveryPriceVo priceSnapshot;

    /**
     * 支付方式（1346，ws_order 派生，D-214）：2 全余额；3 水量抵扣水费+余额付配送费。
     * 页面据此分流价格快照展示——payWay=3 的 waterAmountFen 恒 0，必须呈现为
     * 「水量抵扣」而不是 0 元水费；抵扣升数由前端按容器规格×数量换算（与后端同表）。
     */
    @Schema(description = "支付方式(1346)：2水卡余额 3水卡水量+余额付配送费")
    private Integer payWay;

    @Schema(description = "任务状态(1351)：1待接单 2已接单 3配送中 4已送达待确认 5已签收 6已取消 7申诉中")
    private Integer taskStatus;

    @Schema(description = "乐观锁版本（每次转换+1，动作必须回传）")
    private Integer version;

    @Schema(description = "预约配送时间；空=即时单")
    private String scheduledTime;

    @Schema(description = "接单时间")
    private String acceptTime;

    @Schema(description = "离站时间")
    private String departTime;

    @Schema(description = "送达时间")
    private String arriveTime;

    @Schema(description = "签收时间（服务端生成，规则13）")
    private String signTime;

    @Schema(description = "申诉截止时间（签收+24h，签收事务落定的权威值）")
    private String appealDeadline;

    @Schema(description = "签收定位记录状态(1353)：1已记录 2未记录；签收前为空")
    private Integer locationStatus;

    @Schema(description = "签收三照（受控媒体键引用，时间与签收动作同源）")
    private List<MiniSignPhotoVo> signPhotos;

    /** 价格快照（分）；totalAmountFen 恒等于水费+配送费，由服务端计算，前端只核验不重算。 */
    @Data
    @Accessors(chain = true)
    @Schema(name = "MiniDeliveryPriceVo", description = "配送价格快照")
    public static class MiniDeliveryPriceVo implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "水费快照(分)")
        private Long waterAmountFen;

        @Schema(description = "配送费快照(分)")
        private Long deliveryFeeFen;

        @Schema(description = "总额(分)=水费+配送费")
        private Long totalAmountFen;
    }

    /** 签收单照（三照缺一不可；type 1门牌 2水品 3摆放）。 */
    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "MiniSignPhotoVo", description = "签收照片")
    public static class MiniSignPhotoVo implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "照片类型：1门牌 2水品 3摆放")
        private Integer type;

        @Schema(description = "受控媒体键（ws_delivery_media.MEDIA_KEY）")
        private String mediaKey;

        @Schema(description = "照片权威时间（=签收时间，规则13）")
        private String time;

        @Schema(description = "纬度（定位已记录时存在）")
        private Double latitude;

        @Schema(description = "经度（定位已记录时存在）")
        private Double longitude;
    }

    @Schema(description = "是否为补送任务（E2E-04 包C/包E）。由 ws_after_sale_action.RESULT_TASK_ID 反查得出，"
            + "不是按「金额为 0 / 无回收桶」推断——那种推断会把任何零金额任务误标成补送")
    private Boolean isResend;
}

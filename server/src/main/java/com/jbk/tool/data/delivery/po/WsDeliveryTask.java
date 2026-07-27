package com.jbk.tool.data.delivery.po;

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
 * 配送任务表 Po（E2E-03 履约单：一单一任务，uk_dtask_order 库层唯一）。
 * <p>状态机 1待接单→2已接单→3配送中→4已送达待确认→5已签收；每次转换必须
 * WHERE 当前态+VERSION+配送员归属 的条件 UPDATE（规则10/11），禁止读-改-写。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_delivery_task")
@Schema(name = "WsDeliveryTask", description = "配送任务表")
public class WsDeliveryTask extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "任务号(max32)：DT+sha256(orderNo)前30位，确定性派生")
    @TableField("TASK_NO")
    private String taskNo;

    @Schema(description = "配送订单ID（ws_order，ORDER_TYPE=3）")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "收货用户ID（ws_user.ID）；据此排除自配送（铁律7）")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "配送水站ID快照；配送员服务范围判定只吃本列")
    @TableField("STATION_ID")
    private Long stationId;

    @Schema(description = "接单配送员ID（ws_courier.ID，待接单为空）")
    @TableField("COURIER_ID")
    private Long courierId;

    @Schema(description = "水种ID（ws_water_type.ID，03-demo-baseline 补列）")
    @TableField("WATER_TYPE_ID")
    private Long waterTypeId;

    @Schema(description = "水种名称快照(max20)")
    @TableField("WATER_TYPE")
    private String waterType;

    @Schema(description = "容器规格(max20)：3L袋/5L桶/10L桶/20L桶")
    @TableField("CONTAINER_SPEC")
    private String containerSpec;

    @Schema(description = "计划配送数量(桶/袋)")
    @TableField("DELIVERY_COUNT")
    private Integer deliveryCount;

    @Schema(description = "计划回收空桶数量")
    @TableField("PLAN_RETURN_COUNT")
    private Integer planReturnCount;

    @Schema(description = "实际配送数量（签收时落库）")
    @TableField("ACTUAL_DELIVERY_COUNT")
    private Integer actualDeliveryCount;

    @Schema(description = "实际回收数量（签收时落库）")
    @TableField("ACTUAL_RETURN_COUNT")
    private Integer actualReturnCount;

    @Schema(description = "水费快照(分)")
    @TableField("WATER_AMOUNT")
    private Long waterAmount;

    @Schema(description = "配送费快照(分)；订单总额=水费+配送费（规则2）")
    @TableField("DELIVERY_FEE")
    private Long deliveryFee;

    @Schema(description = "收水地址(max200)")
    @TableField("RECEIVE_ADDRESS")
    private String receiveAddress;

    @Schema(description = "收货电话(max20)，对外展示必须 PhoneMask 脱敏")
    @TableField("RECEIVE_PHONE")
    private String receivePhone;

    @Schema(description = "任务状态(1351)：1待接单 2已接单 3配送中 4已送达待确认 5已签收 6已取消 7申诉中")
    @TableField("TASK_STATUS")
    private Integer taskStatus;

    @Schema(description = "乐观锁版本：创建=1，每次转换+1（规则11）")
    @TableField("VERSION")
    private Integer version;

    @Schema(description = "预约配送时间；空=即时单，非空=到点(<=now)才入池（规则19）")
    @TableField("SCHEDULED_TIME")
    private String scheduledTime;

    @Schema(description = "接单时间")
    @TableField("ACCEPT_TIME")
    private String acceptTime;

    @Schema(description = "离站时间")
    @TableField("DEPART_TIME")
    private String departTime;

    @Schema(description = "送达时间")
    @TableField("ARRIVE_TIME")
    private String arriveTime;

    @Schema(description = "签收时间（服务端生成，规则13）")
    @TableField("SIGN_TIME")
    private String signTime;

    @Schema(description = "签收三照JSON数组：{type, mediaKey, lat, lng, time}")
    @TableField("SIGN_PHOTOS")
    private String signPhotos;

    @Schema(description = "签收定位记录状态(1353)：1已记录 2未记录，签收前为空")
    @TableField("LOCATION_STATUS")
    private Integer locationStatus;

    @Schema(description = "申诉截止时间（签收+24h，规则14）")
    @TableField("APPEAL_DEADLINE")
    private String appealDeadline;

    @Schema(description = "备注(max500)")
    @TableField("TASK_REMARK")
    private String taskRemark;
}

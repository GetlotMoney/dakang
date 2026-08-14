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
 * 商城履约任务 Po（E2E-09 S3）：一单一任务。收货四要素是下单时冻结快照，履约期间不回查地址表。
 * 与一期 {@code WsDeliveryTask} 刻意分表分状态，防商城售后按任务检索串到水配送单。
 *
 * @author dakang
 * @since 2026-08-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_fulfillment")
public class WsMallFulfillment extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "商城订单ID")
    @TableField("ORDER_ID")
    private Long orderId;

    @Schema(description = "商城订单号快照：三端共用同一编号")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "收货用户ID")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "前置仓ID快照")
    @TableField("WAREHOUSE_ID")
    private Long warehouseId;

    @Schema(description = "配送员ID；未分配为空")
    @TableField("COURIER_ID")
    private Long courierId;

    @Schema(description = "履约状态(1396)")
    @TableField("FULFILL_STATUS")
    private Integer fulfillStatus;

    @Schema(description = "履约渠道(1404)：0未确定 1自营配送 2第三方物流；由分配配送员或创建运单 CAS 冻结，冻结后不可改")
    @TableField("FULFILL_MODE")
    private Integer fulfillMode;

    @Schema(description = "乐观锁版本")
    @TableField("VERSION")
    private Integer version;

    @Schema(description = "收货人快照")
    @TableField("RECEIVER_NAME")
    private String receiverName;

    @Schema(description = "收货电话快照：展示必须脱敏")
    @TableField("RECEIVER_PHONE")
    private String receiverPhone;

    @Schema(description = "收货区域快照")
    @TableField("RECEIVER_REGION")
    private String receiverRegion;

    @Schema(description = "收货详细地址快照")
    @TableField("RECEIVER_ADDRESS")
    private String receiverAddress;

    @Schema(description = "收货区县码快照")
    @TableField("RECEIVER_DISTRICT_CODE")
    private String receiverDistrictCode;

    @Schema(description = "拣货时间")
    @TableField("PICK_TIME")
    private String pickTime;

    @Schema(description = "打包完成时间")
    @TableField("PACK_TIME")
    private String packTime;

    @Schema(description = "分配配送员时间")
    @TableField("ASSIGN_TIME")
    private String assignTime;

    @Schema(description = "配送员取货时间")
    @TableField("FETCH_TIME")
    private String fetchTime;

    @Schema(description = "送达时间")
    @TableField("ARRIVE_TIME")
    private String arriveTime;

    @Schema(description = "签收时间")
    @TableField("SIGN_TIME")
    private String signTime;

    @Schema(description = "签收方式(1398)")
    @TableField("SIGN_METHOD")
    private Integer signMethod;

    @Schema(description = "签收备注")
    @TableField("SIGN_REMARK")
    private String signRemark;

    @Schema(description = "履约备注")
    @TableField("FULFILL_REMARK")
    private String fulfillRemark;
}

package com.jbk.tool.data.trade.po;

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
 * 订单表 Po（取水/购卡充值/配送共用主表，ORDER_TYPE 区分）。
 * <p>资金铁律：金额存分、水量存毫升；ORDER_NO 库层唯一（uk_order_no）保证幂等；
 * 状态机单向迁移（1待支付→2已支付→3出水中→4已完成，异常 6/退款 7）。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_order")
@Schema(name = "WsOrder", description = "订单表")
public class WsOrder extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "订单号(max32)，系统生成，微信 out_trade_no")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "订单类型(1340)：1扫码取水 2购卡充值 3水配送")
    @TableField("ORDER_TYPE")
    private Integer orderType;

    @Schema(description = "下单用户ID（ws_user.ID）")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "水站ID（取水/配送单必填）")
    @TableField("STATION_ID")
    private Long stationId;

    @Schema(description = "设备ID（取水单必填）")
    @TableField("DEVICE_ID")
    private Long deviceId;

    @Schema(description = "出水口ID（取水单必填）")
    @TableField("OUTLET_ID")
    private Long outletId;

    @Schema(description = "支付用水卡ID（卡支付时必填）")
    @TableField("CARD_ID")
    private Long cardId;

    @Schema(description = "套餐ID（购卡充值单必填）")
    @TableField("PACKAGE_ID")
    private Long packageId;

    @Schema(description = "套餐/价格快照JSON（下单时价格、单价、幂等 requestId，退款折算依据）")
    @TableField("PACKAGE_SNAP")
    private String packageSnap;

    @Schema(description = "计划水量(毫升)（取水单）")
    @TableField("PLAN_ML")
    private Long planMl;

    @Schema(description = "实际水量(毫升)（设备回传后回填，异常补偿依据）")
    @TableField("ACTUAL_ML")
    private Long actualMl;

    @Schema(description = "订单金额(分)")
    @TableField("ORDER_AMOUNT")
    private Long orderAmount;

    @Schema(description = "支付方式(1346)：1微信支付 2水卡余额 3水卡水量")
    @TableField("PAY_WAY")
    private Integer payWay;

    @Schema(description = "订单状态(1341)：1待支付 2已支付 3出水中 4已完成 5已取消 6异常待补偿 7已退款 8部分退款")
    @TableField("ORDER_STATUS")
    private Integer orderStatus;

    @Schema(description = "关联出水指令ID（ws_command.ID）")
    @TableField("CMD_ID")
    private Long cmdId;

    @Schema(description = "完成时间")
    @TableField("FINISH_TIME")
    private String finishTime;

    @Schema(description = "取消/异常原因(max500)")
    @TableField("CANCEL_REASON")
    private String cancelReason;
}

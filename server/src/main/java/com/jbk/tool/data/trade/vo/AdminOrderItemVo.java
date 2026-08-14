package com.jbk.tool.data.trade.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 管理端订单列表项 Vo（PC 订单中心）。
 * <p>
 * 字段严格对齐前端 {@code client/src/api/order.ts} 的 OrderItem 契约（保 H2-FE 类型零改）。
 * 派生字段来源：userName/userPhoneRaw 关联 ws_user，stationName 关联 ws_station，
 * deviceNo 关联 ws_device，outletNo 关联 ws_device_outlet，cardNo 关联 ws_card。
 * 下单人号码只以 actorMaskedPhone（脱敏）出接口，原值走 userPhoneRaw 且不序列化。
 * Long 型经全局 Jackson 序列化为字符串防 JS 精度丢失，前端接真时归一化。
 * </p>
 *
 * @author dakang
 * @since 2026-07-20
 */
@Data
@Schema(name = "AdminOrderItemVo", description = "管理端订单列表项")
public class AdminOrderItemVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单ID")
    private Long id;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "订单类型(1340)：1扫码取水 2购卡充值 3水配送")
    private Integer orderType;

    @Schema(description = "下单用户ID")
    private Long userId;

    @Schema(description = "下单用户姓名（关联 ws_user 派生）")
    private String userName;

    /**
     * SQL 取出的下单人原始手机号，仅作 Service 层脱敏输入，出接口的是 {@code actorMaskedPhone}。
     * 与 {@code cardOwnerPhoneRaw} 同口径：{@code @JsonIgnore} 兜底保证即使某条装配路径漏调脱敏，
     * 原始号码也不会被序列化——订单分页是全量 C 端手机号最大的一个批量出口，不接受「靠调用方自觉」。
     */
    @Schema(hidden = true)
    @JsonIgnore
    private String userPhoneRaw;

    @Schema(description = "水站ID")
    private Long stationId;

    @Schema(description = "水站名称（关联 ws_station 派生）")
    private String stationName;

    @Schema(description = "设备ID")
    private Long deviceId;

    @Schema(description = "设备编号（关联 ws_device 派生）")
    private String deviceNo;

    @Schema(description = "出水口ID")
    private Long outletId;

    @Schema(description = "出水口序号（关联 ws_device_outlet 派生）")
    private Integer outletNo;

    @Schema(description = "出水口所属设备ID（关联 ws_device_outlet 派生，仅用于履约共键校验）")
    private Long outletDeviceId;

    @Schema(description = "水卡ID")
    private Long cardId;

    @Schema(description = "卡号（关联 ws_card 派生）")
    private String cardNo;

    @Schema(description = "套餐ID")
    private Long packageId;

    @Schema(description = "套餐快照JSON（充值单）")
    private String packageSnap;

    @Schema(description = "计划出水量(毫升)")
    private Long planMl;

    @Schema(description = "实际出水量(毫升)")
    private Long actualMl;

    @Schema(description = "订单金额(分)")
    private Long orderAmount;

    @Schema(description = "支付方式(1346)：1微信支付 2水卡余额 3水卡水量")
    private Integer payWay;

    @Schema(description = "订单状态(1341)")
    private Integer orderStatus;

    @Schema(description = "关联出水指令ID")
    private Long cmdId;

    @Schema(description = "完成时间")
    private String finishTime;

    @Schema(description = "取消/异常原因")
    private String cancelReason;

    @Schema(description = "下单时间")
    private String createTime;

    // ------------------------------------------------------------------
    // 支付单派生字段（L2-T）。无支付单的订单（如扫码取水）这些字段为 null。
    // ------------------------------------------------------------------

    @Schema(description = "支付状态(1342)：1待支付 2成功 4已关闭；无支付单为空")
    private Integer payStatus;

    /**
     * 后台唯一能区分「真实微信收款」与「Pay-Sim 模拟收款」的字段。
     * 缺了它，演示环境造的单在对账口径里与真钱不可分辨。
     */
    @Schema(description = "支付来源：1微信 2Pay-Sim；无支付单为空")
    private Integer paySource;

    @Schema(description = "支付方交易号；未支付为空")
    private String transactionId;

    @Schema(description = "不可变付款截止时间")
    private String payExpireTime;

    @Schema(description = "支付成功时间；未支付为空")
    private String paySuccessTime;

    @Schema(description = "支付单逻辑删除标记：非 0 即该订单的支付单已被删除，属需人工核查的异常")
    private Integer paymentDataStatus;

    // ------------------------------------------------------------------
    // 使用人/持卡人身份聚合（UI-TRACE PC 追溯读侧）。
    // 启用 CARD-MEMBER 后，ws_order.USER_ID 是实际使用人（可能是成员），持卡人以 ws_card.USER_ID 为准；
    // 两个身份由服务端算好下发，前端不做推导。脱敏在 Service 层完成，原始手机号不出接口。
    // ------------------------------------------------------------------

    @Schema(description = "实际使用人脱敏手机号（服务端脱敏；用户缺失或号码异常为空串）")
    private String actorMaskedPhone;

    @Schema(description = "持卡人用户ID（经 CARD_ID → ws_card.USER_ID；卡缺失为空）")
    private Long cardOwnerUserId;

    @Schema(description = "持卡人姓名（关联 ws_user 派生；卡或用户缺失为空）")
    private String cardOwnerName;

    @Schema(description = "持卡人脱敏手机号（服务端脱敏；卡/用户缺失或号码异常为空串）")
    private String cardOwnerMaskedPhone;

    /**
     * SQL 取出的持卡人原始手机号，仅作 Service 层脱敏输入。
     * {@code @JsonIgnore} 兜底保证即使脱敏步骤被绕过，原始号码也不会被序列化出接口。
     */
    @Schema(hidden = true)
    @JsonIgnore
    private String cardOwnerPhoneRaw;

    @Schema(description = "用卡角色：OWNER=使用人即持卡人 / MEMBER=成员用卡；无卡或持卡人不可知时为空")
    private String accessRole;

    @Schema(description = "卡当前余额(分)（ws_card.BALANCE_AMOUNT 只读快照；卡缺失为空）")
    private Long cardBalanceFen;

    @Schema(description = "卡当前剩余水量(毫升)（ws_card.BALANCE_ML 只读快照；卡缺失为空）")
    private Long cardBalanceMl;

    /**
     * 取水异常核账确认标记（E2E-04 包A）：ws_after_sale_action 存在 SOURCE_TYPE=3 且 ACTION_STATUS=3 的行。
     * 由订单查询 SQL 用 EXISTS 一并带出（Service 逐行查即 N+1）；缺了它，PC 追溯会把已核账订单全报成 mismatch。
     */
    @Schema(description = "是否已经过取水异常核账确认（服务端派生，用于订单-指令状态矩阵判定）")
    private Boolean afterSaleConfirmed;
}

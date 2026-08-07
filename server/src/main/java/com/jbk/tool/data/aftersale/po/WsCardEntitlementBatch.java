package com.jbk.tool.data.aftersale.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 水卡权益批次 Po（E2E-04 包D，REQ-061）。
 *
 * <p>每笔充值恰好一个批次（{@code uk_batch_order} 物理保证）。
 * 退款折算的每一个输入都取自本行与 {@code PACKAGE_SNAP}，
 * <b>绝不查当前套餐、也绝不用 ws_card 的聚合余额</b>——理由见 EntitlementRefundMath 的类注释。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_card_entitlement_batch")
@Schema(name = "WsCardEntitlementBatch", description = "水卡权益批次表")
public class WsCardEntitlementBatch extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属水卡ID")
    private Long cardId;

    @Schema(description = "卡主用户ID")
    private Long userId;

    @Schema(description = "批次来源(1375)：1首次购卡 2已有卡充值 3历史聚合（不可退） 4运营赠卡（恒不可退）")
    private Integer sourceType;

    @Schema(description = "来源充值订单ID；历史聚合批次为空")
    private Long orderId;

    @Schema(description = "来源充值订单号")
    private String orderNo;

    @Schema(description = "来源支付单ID")
    private Long paymentId;

    @Schema(description = "套餐ID")
    private Long packageId;

    @Schema(description = "套餐快照；折算公式的 payAmountFen/grantedWaterMl 只能取自这里")
    private String packageSnap;

    @Schema(description = "本批次实付金额(分)")
    private Long payAmountFen;

    @Schema(description = "发放的余额权益(分)=本金+赠送")
    private Long grantAmountFen;

    @Schema(description = "其中赠送部分(分)；赠送已消费部分不退款")
    private Long grantBonusFen;

    @Schema(description = "发放的水量权益(毫升)")
    private Long grantWaterMl;

    @Schema(description = "剩余余额权益(分)")
    private Long remainAmountFen;

    @Schema(description = "剩余水量权益(毫升)")
    private Long remainWaterMl;

    @Schema(description = "本批次有效期；空=永久。批次选取时空值视为最晚")
    private String expireTime;

    @Schema(description = "本批次可用范围快照")
    private String scopeJson;

    @Schema(description = "批次状态(1374)：1可用 2退款锁定 3已退款 4已耗尽 5已过期 6不可退")
    private Integer batchStatus;

    @Schema(description = "锁定该批次的售后动作ID")
    private Long refundLockedBy;

    @Schema(description = "锁定时间")
    private String refundLockTime;

    @Schema(description = "已退款金额(分)")
    private Long refundedAmountFen;

    @Schema(description = "乐观锁版本")
    private Integer version;
}

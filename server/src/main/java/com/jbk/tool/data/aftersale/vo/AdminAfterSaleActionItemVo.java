package com.jbk.tool.data.aftersale.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * PC 售后台账行（E2E-04 包A）。列集严格对齐 {@code WsAfterSaleActionMapper.xml} 的
 * {@code Admin_Action_Columns} 别名；改一侧必须改另一侧，否则字段静默为 null。
 * 手机号走 userPhoneRaw（@JsonIgnore）+ userMaskedPhone 两列，Service 统一脱敏，明文不出接口。
 * 四元额度分列下发，原因见 ws_after_sale_action 表注释。
 *
 * @author dakang
 * @since 2026-07-29
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "AdminAfterSaleActionItemVo", description = "售后台账行")
public class AdminAfterSaleActionItemVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "售后动作ID")
    private Long id;

    @Schema(description = "售后号（确定性派生，同来源重放得同号）")
    private String afterSaleNo;

    @Schema(description = "售后来源(1370)：1配送取消 2配送申诉 3取水异常核账")
    private Integer sourceType;

    @Schema(description = "来源主体ID：配送取消/取水核账取订单ID，配送申诉取申诉ID")
    private Long sourceId;

    @Schema(description = "关联订单ID")
    private Long orderId;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "订单类型(1340)：1扫码取水 2购卡充值 3水配送")
    private Integer orderType;

    @Schema(description = "订单状态(1341)")
    private Integer orderStatus;

    @Schema(description = "支付方式(1346)：2水卡余额 3水卡水量")
    private Integer payWay;

    @Schema(description = "充值支付来源：1微信支付 2Pay-Sim；非充值订单或无支付记录时为空")
    private Integer paySource;

    @Schema(description = "订单金额(分)")
    private Long orderAmount;

    @Schema(description = "订单归属用户ID")
    private Long userId;

    @Schema(description = "用户姓名")
    private String userName;

    @Schema(description = "用户手机号（脱敏后）")
    private String userMaskedPhone;

    /** SQL 取原值供 Service 脱敏；@JsonIgnore 兜底，任何情况下不出接口。 */
    @JsonIgnore
    @Schema(hidden = true)
    private String userPhoneRaw;

    @Schema(description = "返还目标卡ID")
    private Long cardId;

    @Schema(description = "卡号")
    private String cardNo;

    @Schema(description = "动作类型(1371)：1卡内退款 2卡内补偿 3机构退款 4补送")
    private Integer actionType;

    @Schema(description = "补偿策略码：PRODUCT_ONLY/SERVICE_FEE_ONLY/PRODUCT_AND_SERVICE/RESEND/REJECT")
    private String strategyCode;

    @Schema(description = "运营批准的受影响数量(桶)")
    private Integer approvedCount;

    @Schema(description = "水品权益返还金额(分)，payWay=2 专用")
    private Long refundProductFen;

    @Schema(description = "配送费返还金额(分)")
    private Long refundServiceFen;

    @Schema(description = "水品权益返还水量(毫升)，payWay=3 专用")
    private Long refundProductMl;

    @Schema(description = "返还金额合计(分)=水品+配送费；封顶判定不看本列，见表注释")
    private Long refundAmount;

    @Schema(description = "执行状态(1372)：1待执行 2执行中 3已完成 4可重试 5需人工对账 6已终止")
    private Integer actionStatus;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "重试次数")
    private Integer retryCount;

    @Schema(description = "下次可重试时间")
    private String nextRetryTime;

    @Schema(description = "批准人员工ID")
    private Long approveBy;

    @Schema(description = "批准人姓名")
    private String approveByName;

    @Schema(description = "批准时间")
    private String approveTime;

    @Schema(description = "终态时间")
    private String finishTime;

    @Schema(description = "最近一次失败原因")
    private String lastError;

    @Schema(description = "创建时间")
    private String createTime;
}

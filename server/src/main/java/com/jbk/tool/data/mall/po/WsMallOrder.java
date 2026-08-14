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
 * 商城订单 Po（E2E-09 S2）：一单一仓，收货信息为下单快照。
 *
 * <p>金额三列的恒等式（总额=商品金额+配送费）由库层 CHECK 兜底；一切金额由服务端
 * 按 SKU 现价重算，前端传入的金额不参与任何计算。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_order")
public class WsMallOrder extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "商城订单号：MO+sha256(userId:requestId)前30位")
    @TableField("ORDER_NO")
    private String orderNo;

    @Schema(description = "下单用户ID")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "履约前置仓ID：创单选定并冻结")
    @TableField("WAREHOUSE_ID")
    private Long warehouseId;

    @Schema(description = "创单请求号（规范小写 UUID）：与用户组唯一键=幂等锚")
    @TableField("REQUEST_ID")
    private String requestId;

    @Schema(description = "来源地址簿行ID：仅用于重放参数等价判定")
    @TableField("ADDRESS_ID")
    private Long addressId;

    @Schema(description = "商品金额(分)")
    @TableField("PRODUCT_AMOUNT_FEN")
    private Long productAmountFen;

    @Schema(description = "配送费(分)：内部闭环期固定0并落快照")
    @TableField("DELIVERY_FEE_FEN")
    private Long deliveryFeeFen;

    @Schema(description = "订单总额(分)：恒等于商品金额+配送费")
    @TableField("ORDER_AMOUNT_FEN")
    private Long orderAmountFen;

    @Schema(description = "订单状态(1393)：1待支付 2已支付待履约 3履约中 4已完成 5已取消 6已全额退款")
    @TableField("ORDER_STATUS")
    private Integer orderStatus;

    @Schema(description = "支付截止时间：创单冻结，到期本身不构成关闭依据")
    @TableField("PAY_EXPIRE_TIME")
    private String payExpireTime;

    @Schema(description = "收货人姓名快照")
    @TableField("RECEIVER_NAME")
    private String receiverName;

    @Schema(description = "收货电话快照（原号）：出参必须经 PhoneMask 脱敏")
    @TableField("RECEIVER_PHONE")
    private String receiverPhone;

    @Schema(description = "省市区文本快照")
    @TableField("RECEIVER_REGION")
    private String receiverRegion;

    @Schema(description = "详细地址快照")
    @TableField("RECEIVER_ADDRESS")
    private String receiverAddress;

    @Schema(description = "收货区县行政区码快照(6位)：选仓判据")
    @TableField("RECEIVER_DISTRICT_CODE")
    private String receiverDistrictCode;

    @Schema(description = "取消/支付关闭时间")
    @TableField("CANCEL_TIME")
    private String cancelTime;

    @Schema(description = "取消原因")
    @TableField("CANCEL_REASON")
    private String cancelReason;

    @Schema(description = "换货补发来源售后单ID：非空即内部零价补发单，不计新销售收入")
    @TableField("SOURCE_AFTER_SALE_ID")
    private Long sourceAfterSaleId;

    @Schema(description = "乐观锁版本：每次状态迁移+1")
    @TableField("VERSION")
    private Integer version;
}

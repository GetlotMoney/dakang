package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 机主交易快照明细行 Vo（E2E-06，对齐 miniapp device.ts OwnerTransactionItem 冻结契约）。
 *
 * <p>脱敏铁律（D-212）：本 Vo 禁止出现下单用户任何身份字段（USER_ID/手机号/openid）——
 * 机主看经营，不看客户是谁。订单状态如实返回（含全额/部分退款），金额为毛额不冲减。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniOwnerTransactionVo", description = "机主交易快照明细行")
public class MiniOwnerTransactionVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "订单号（三端贯穿业务键）")
    private String orderNo;

    @Schema(description = "订单类型(1340)：1扫码取水 3水配送（充值单永不出现）")
    private Integer orderType;

    @Schema(description = "订单状态(1341)：如实返回，含 7全额退款/8部分退款")
    private Integer orderStatus;

    @Schema(description = "水站 ID")
    private Long stationId;

    @Schema(description = "水站名称")
    private String stationName;

    @Schema(description = "设备编号（配送单无设备为 null）")
    private String deviceNo;

    @Schema(description = "实际出水量（毫升；配送单/未结算为 null）")
    private Long actualVolumeMl;

    @Schema(description = "订单金额毛额（分）")
    private Long orderAmountFen;

    @Schema(description = "下单时间")
    private String createTime;
}

package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 机主经营概览 Vo（E2E-06，对齐 miniapp device.ts OwnerOverview 冻结契约）。
 *
 * <p>口径（任务书 3.1/3.2 冻结）：金额=订单成交毛额（分），退款不冲减；订单范围=
 * 站轨∪设备轨去重，排除待支付/已取消，充值单永不计入；设备计数按名下设备；
 * 计数用 Integer（demo 级），金额/水量用 Long（前端按 strictNumber 手法归一化
 * 全局 Long→字符串序列化）。</p>
 *
 * @author dakang
 * @since 2026-07-31
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniOwnerOverviewVo", description = "机主经营概览（订单口径毛额，只读快照）")
public class MiniOwnerOverviewVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "统计周期起（近7自然日含今日，服务器时钟推算）")
    private String periodStart;

    @Schema(description = "统计周期止（服务器当前时间）")
    private String periodEnd;

    @Schema(description = "授权水站数（名下水站 ∪ 名下设备挂靠水站，去重）")
    private Integer stationCount;

    @Schema(description = "名下设备数")
    private Integer deviceCount;

    @Schema(description = "在线设备数")
    private Integer onlineCount;

    @Schema(description = "离线设备数（含未激活）")
    private Integer offlineCount;

    @Schema(description = "故障设备数（RUN_STATUS=3）")
    private Integer faultCount;

    @Schema(description = "周期订单数（排除待支付/已取消；充值单不计入）")
    private Integer orderCount;

    @Schema(description = "周期实际出水量合计（毫升）")
    private Long actualVolumeMl;

    @Schema(description = "周期订单金额毛额合计（分）；退款不冲减，净收益归 E2E-08")
    private Long orderAmountFen;

    /**
     * 水卡水量支付产生的出水量（毫升）——这部分水的水费在用户充值环节已结算，
     * 充值单不属于任何机主（STATION_ID 为空），因此它对本期金额的贡献恒为 0。
     * 单列该口径是为了让机主看懂「出水很多、金额很少」不是漏记：不解释就会被当成缺数。
     */
    @Schema(description = "本期出水中由水卡水量支付的部分（毫升）；其水费已在充值环节结算，不计入本期金额")
    private Long prepaidVolumeMl;

    @Schema(description = "证据模式：real=真实库聚合")
    private String evidenceMode;
}

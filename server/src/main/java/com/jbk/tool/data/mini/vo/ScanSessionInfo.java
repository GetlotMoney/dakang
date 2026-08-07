package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 扫码会话内部数据（供下单链复用 L1a 会话，非对外 API 字段）。
 * <p>由 IMiniDeviceService.loadScanSession 返回：已校验会话归属登录人且未过期。</p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "ScanSessionInfo", description = "扫码会话内部数据")
public class ScanSessionInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话本身的 ID：订单快照的 requestId 即此值，事务内据此核「快照出自本次扫码」。 */
    @Schema(description = "扫码会话ID")
    private String scanSessionId;

    @Schema(description = "会话铸造用户ID")
    private Long userId;

    @Schema(description = "二维码ID（CARD-SCOPE：供下单事务在锁内重核 qrcode/station/device/outlet 共键）")
    private Long qrcodeId;

    @Schema(description = "水站ID（扫码时按设备档案铸造，下单事务据此重核 device.stationId 未漂移）")
    private Long stationId;

    @Schema(description = "设备ID")
    private Long deviceId;

    @Schema(description = "设备编号")
    private String deviceNo;

    @Schema(description = "出水口ID")
    private Long outletId;

    /**
     * 扫码时冻结的水种（S2）。价格与水种的唯一权威来源是这份会话——
     * 确认页展示、下单装配、事务内终判全都吃它，前端一律不得自报。
     */
    @Schema(description = "冻结水种ID（扫码时点）")
    private Long waterTypeId;

    @Schema(description = "冻结单价（分/升，扫码时点）")
    private Integer unitPriceFenPerLiter;

    @Schema(description = "报价生成时间 yyyyMMddHHmmss")
    private String quotedAt;
}

package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 扫码解析结果 Vo（对齐 miniapp device.ts ScanSession，字段名不可改）。
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "ScanSessionVo", description = "扫码会话")
public class ScanSessionVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "扫码会话ID（服务端 UUID，Redis 一次性，TTL 300s）")
    private String scanSessionId;

    @Schema(description = "设备编号")
    private String deviceNo;

    @Schema(description = "出水口ID")
    private Long outletId;

    @Schema(description = "会话过期时间 yyyyMMddHHmmss（下单前须在有效期内）")
    private String expiresAt;
}

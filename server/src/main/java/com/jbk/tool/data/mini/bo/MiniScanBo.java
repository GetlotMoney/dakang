package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 小程序扫码/取水请求对象。resolve 用 rawCode；water/context 与 water/eligibility 用 scanSessionId，
 * 必填校验在 Service 层按方法做。不含 userId：登录人一律由 KH_USER 会话取（铁律6，禁止前端传参圈定数据范围）。
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "MiniScanBo", description = "小程序扫码/取水请求对象")
public class MiniScanBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "扫码原文（resolve 必填），对应 ws_qrcode.QRCODE_CONTENT")
    private String rawCode;

    @Schema(description = "扫码会话ID（water/context、water/eligibility 必填），resolve 返回")
    private String scanSessionId;

    @Schema(description = "待预检的水卡ID（water/eligibility 用，CARD-SCOPE）：后端只检查该卡，"
            + "杜绝「预检卡A、下单卡B」；不传时按未持卡返回 CARD_MISSING 阻断")
    private Long cardId;
}

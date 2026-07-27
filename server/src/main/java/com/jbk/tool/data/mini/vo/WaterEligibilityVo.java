package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 取水资格 Vo（对齐 miniapp device.ts WaterEligibility）。
 * <p>
 * 双分组：设备组 availability（DeviceAvailability 封闭枚举）+ 卡组 cardBlock；两组均通过才可下单。
 * availability ∈
 * AVAILABLE/DEVICE_OFFLINE/FAULT_E001/FAULT_E003/FAULT_E004/DEVICE_UNAVAILABLE/NO_AVAILABLE_OUTLET；
 * 未知/未收录故障码不扩展枚举，归 DEVICE_UNAVAILABLE 并在 reason 带具体码（产品规则 v1：未知默认阻断）。
 * </p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WaterEligibilityVo", description = "取水资格")
public class WaterEligibilityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "设备可用性：AVAILABLE 可用，其余为阻断原因")
    private String availability;

    @Schema(description = "阻断说明（availability 非 AVAILABLE 时给具体原因，含未知故障码）")
    private String reason;

    @Schema(description = "水量支付参考上限(毫升)=指定水卡剩余水量；只用于水量支付比对，余额支付不受其阻断（CARD-SCOPE）")
    private Long maxAllowedMl;

    @Schema(description = "成员日剩余额度(毫升)；仅成员取水时非空，不得与水量余量混用")
    private Long remainingDailyLimitMl;

    @Schema(description = "卡侧阻断（为空表示卡可用）")
    private CardBlockVo cardBlock;

    /**
     * 卡侧阻断信息（对齐 miniapp CardBlockCode）。
     * code ∈ CARD_MISSING/CARD_NOT_ACCESSIBLE/CARD_SCOPE_INVALID/CARD_SCOPE_DENIED/
     * CARD_FROZEN/CARD_EXPIRED/CARD_CANCELLED。
     * 他人卡与不存在卡统一 CARD_NOT_ACCESSIBLE，不泄露卡是否存在。
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    @Schema(name = "CardBlockVo", description = "卡侧阻断")
    public static class CardBlockVo implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "卡阻断码：CARD_MISSING/CARD_NOT_ACCESSIBLE/CARD_SCOPE_INVALID/"
                + "CARD_SCOPE_DENIED/CARD_FROZEN/CARD_EXPIRED/CARD_CANCELLED")
        private String code;

        @Schema(description = "卡阻断说明")
        private String message;
    }
}

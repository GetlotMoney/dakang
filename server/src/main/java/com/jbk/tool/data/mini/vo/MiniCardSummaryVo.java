package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 小程序主水卡摘要 Vo（L1f 水卡余额真实展示）。
 * <p>
 * 字段严格对齐 miniapp {@code CardSummary} 契约，保证小程序端类型零改：
 * 余额字段命名为 {@code balanceFen}（非 PO 的 balanceAmount），前端归一化时优先读取该键。
 * Long 型 cardId/balanceFen/balanceMl 经全局 Jackson 序列化为字符串防 JS 精度丢失，前端再转回 number。
 * </p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Data
@Schema(name = "MiniCardSummaryVo", description = "小程序主水卡摘要")
public class MiniCardSummaryVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "水卡ID（ws_card.ID）")
    private Long cardId;

    @Schema(description = "卡号")
    private String cardNo;

    @Schema(description = "卡类型(1331)：1虚拟卡 2实体卡")
    private Integer cardType;

    @Schema(description = "卡状态(1332)：1正常 2冻结 3已过期 4已注销")
    private Integer cardStatus;

    @Schema(description = "余额(分)，来源 ws_card.BALANCE_AMOUNT")
    private Long balanceFen;

    @Schema(description = "剩余水量(毫升)")
    private Long balanceMl;

    @Schema(description = "到期时间(yyyyMMddHHmmss)，空=永久")
    private String expireTime;
}

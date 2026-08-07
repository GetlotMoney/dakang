package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 赠卡合并结果 Vo（D-415）。
 *
 * <p>{@code movedFen/movedMl} 为 0 且 {@code expiredCleared} 为 true 表示赠卡已过期、
 * 权益已作废，本次只完成了卡的清理注销，没有权益转移。</p>
 */
@Data
@Schema(name = "MiniCardMergeVo", description = "赠卡合并结果")
public class MiniCardMergeVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "目标正式水卡ID")
    private Long mainCardId;

    @Schema(description = "目标正式水卡卡号")
    private String mainCardNo;

    @Schema(description = "转入余额(分)")
    private Long movedFen;

    @Schema(description = "转入水量(毫升)")
    private Long movedMl;

    @Schema(description = "转入权益的到期时间(yyyyMMddHHmmss)；过期作废时为空")
    private String bundleExpireTime;

    @Schema(description = "合并后正式水卡余额(分)")
    private Long mainBalanceFen;

    @Schema(description = "合并后正式水卡水量(毫升)")
    private Long mainBalanceMl;

    @Schema(description = "true=赠卡已过期，权益作废仅完成清理注销")
    private Boolean expiredCleared;
}

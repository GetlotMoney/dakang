package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 小程序水卡详情 Vo（L2-READ：本人卡只读；L2-B 已有卡充值的 cardId 前置数据源）。
 *
 * <p>字段在 {@code CardSummary} 基础上扩展 packageName / scopeDescription / members，
 * 与 miniapp {@code CardDetail} 契约对齐，保证前端类型零改。</p>
 *
 * <p>{@code scopeDescription} 是服务端把 SCOPE_JSON 归纳出的**人类可读描述**，
 * 不下发原始范围 JSON——范围校验只在服务端执行，前端不得据此自行判定可用性。</p>
 */
@Data
@Schema(name = "MiniCardDetailVo", description = "小程序水卡详情")
public class MiniCardDetailVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "水卡ID（ws_card.ID）")
    private Long cardId;

    @Schema(description = "卡号")
    private String cardNo;

    @Schema(description = "卡类型(1331)：1虚拟卡 2实体卡")
    private Integer cardType;

    @Schema(description = "卡状态(1332)：1正常 2冻结 3已过期 4已注销")
    private Integer cardStatus;

    @Schema(description = "余额(分)")
    private Long balanceFen;

    @Schema(description = "剩余水量(毫升)")
    private Long balanceMl;

    @Schema(description = "到期时间(yyyyMMddHHmmss)，空=永久")
    private String expireTime;

    @Schema(description = "最近购买套餐名称（取自 ws_card.PACKAGE_SNAP 快照，空=无）")
    private String packageName;

    @Schema(description = "可用范围的人类可读描述；未配置时明确为『未配置（默认拒绝）』")
    private String scopeDescription;

    @Schema(description = "授权成员列表（含已解除，enabled 标识生效与否）")
    private List<MiniCardMemberVo> members;

    @Schema(description = "是否可合并入正式水卡（D-415：本人赠卡且名下有正式水卡；展示投影，服务端另行强制校验）")
    private Boolean canMergeToPaidCard;

    @Schema(description = "带到期时间的权益批次摘要（按到期升序；合并转入的权益到期展示来源）")
    private List<MiniCardBundleVo> expiringBundles;
}

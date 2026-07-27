package com.jbk.tool.data.mini.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 小程序可用水卡 Vo（CARD-MEMBER：usable-list = 本人持卡 + 当前有效的成员授权卡）。
 *
 * <p>在摘要字段（对齐 miniapp {@code CardSummary}）之上追加角色与能力位：
 * OWNER 可充值可管成员；MEMBER 只能取水——能力位由服务端按角色固定生成，
 * 前端只做展示投影，不构成授权（授权仍在各接口的 Service 层强制校验）。
 * 成员授权卡只下发取水所需最小摘要（本类字段即全集，不含范围 JSON、套餐快照与成员列表）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "MiniUsableCardVo", description = "小程序可用水卡（含访问角色与能力位）")
public class MiniUsableCardVo extends MiniCardSummaryVo {

    private static final long serialVersionUID = 1L;

    /** 访问角色常量：仅 OWNER/MEMBER 两值，未知一律不下发。 */
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_MEMBER = "MEMBER";

    @Schema(description = "访问角色：OWNER=本人持卡 MEMBER=成员授权卡")
    private String accessRole;

    @Schema(description = "是否可充值（仅 OWNER 为 true；能力位只作展示投影，服务端接口另行强制校验）")
    private Boolean canRecharge;

    @Schema(description = "是否可管理成员（仅 OWNER 为 true）")
    private Boolean canManageMembers;

    @Schema(description = "成员今日剩余限额(毫升)；仅 MEMBER 且配置了 DAY_LIMIT_ML 时非空，空=不限或非成员")
    private Long remainingDailyLimitMl;
}

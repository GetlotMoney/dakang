package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

/**
 * 小程序水卡成员授权保存入参（CARD-MEMBER：新增与编辑共用）。
 *
 * <p>铁律6：不收 userId——卡主身份只从 KH_USER 会话取，卡归属在 Service 层 WHERE 内强制。
 * 手机号只用于匹配已存在的唯一用户（不自动建户），响应一律只回脱敏号。</p>
 */
@Data
@Schema(name = "MiniCardMemberSaveBo", description = "小程序水卡成员授权保存入参")
public class MiniCardMemberSaveBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "cardId 不能为空")
    @Schema(description = "水卡ID（必须为会话用户本人持有）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long cardId;

    @Schema(description = "成员记录ID；编辑时必填，新增时留空。编辑不能换人（换人=撤销+重加）")
    private Long memberId;

    @NotBlank(message = "成员备注名不能为空")
    @Size(max = 50, message = "成员备注名最长 50 字")
    @Schema(description = "成员备注名(max50)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String memberName;

    @NotBlank(message = "成员手机号不能为空")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不合法")
    @Schema(description = "成员手机号（仅用于匹配已注册用户，响应只回脱敏号）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @Positive(message = "单日限额必须为正整数")
    @Schema(description = "单日限额(毫升)，空=不限")
    private Long dayLimitMl;

    @Pattern(regexp = "^\\d{14}$", message = "生效时间格式须为 yyyyMMddHHmmss")
    @Schema(description = "授权生效时间(yyyyMMddHHmmss)，空=立即生效")
    private String effectiveTime;

    @Pattern(regexp = "^\\d{14}$", message = "失效时间格式须为 yyyyMMddHHmmss")
    @Schema(description = "授权失效时间(yyyyMMddHHmmss)，空=长期有效；不得早于生效时间")
    private String expireTime;
}

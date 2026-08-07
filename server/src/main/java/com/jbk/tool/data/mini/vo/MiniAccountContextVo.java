package com.jbk.tool.data.mini.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 小程序账号上下文（登录/绑定成功后返回，供前端恢复会话与首页能力投影）。
 * <p>身份 Long ID 一律 string（防 2^53 精度丢失）；**绝不包含 openid / session_key**。</p>
 *
 * @author dakang
 * @since 2026-07-21
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "MiniAccountContextVo", description = "小程序账号上下文（不含微信身份密钥）")
public class MiniAccountContextVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "账号 ID（= userId，string）")
    private String accountId;

    @Schema(description = "用户 ID（string，防精度丢失）")
    private String userId;

    @Schema(description = "用户昵称")
    private String userName;

    @Schema(description = "用户手机号；仅微信身份建号且尚未补绑时不携带本字段")
    private String userPhone;

    @Schema(description = "是否已绑定手机号；false 时前端应展示补绑入口，且不得直接对 userPhone 取子串")
    private Boolean phoneBound;

    @Schema(description = "头像路径（服务端相对路径，如 /mini/profile/avatar/AVxx.jpg）；未设置不携带")
    private String userAvatar;

    @Schema(description = "能力清单（USER_BASE / COURIER_* / OWNER_*）")
    private List<String> capabilities;

    @Schema(description = "机主授权范围（有归属才携带；E2E-06 profile 展示用，不构成授权）")
    private MiniOwnerScopeVo ownerScope;
}

package com.jbk.tool.data.mini.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录后自助更新资料入参（昵称/头像至少提交其一；两者都可单独更新）。
 *
 * <p>头像走统一 POST+JSON 工程惯例（与配送媒体上传同款）：内容 base64 + 显式 MIME。
 * 昵称长度界与 {@code ws_user.USER_NAME varchar(50)} 一致；内容安全检测（微信要求）为上线前项，
 * demo 阶段只做长度与空值校验。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Data
@Schema(name = "MiniProfileUpdateBo", description = "自助更新资料入参（昵称/头像至少其一）")
public class MiniProfileUpdateBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Size(max = 50, message = "昵称长度不能超过50")
    @Schema(description = "新昵称；缺省表示不改昵称")
    private String userName;

    @Schema(description = "头像内容 base64；缺省表示不改头像")
    private String avatarBase64;

    @Schema(description = "头像 MIME（image/jpeg|image/png|image/webp）；与 avatarBase64 成对出现")
    private String avatarMimeType;
}

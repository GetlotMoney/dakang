package com.jbk.tool.data.user.vo;

import com.jbk.tool.data.BaseEntityVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * C 端用户响应对象；不返回身份证密文，也不返回微信 openid（复审 P1-4）。
 *
 * <p>普通用户列表/详情属管理侧只读视图，微信 openid 为身份密钥，不得随普通响应下发。
 * 如后续确需身份核验，另建带独立权限、脱敏与审计的专用接口，不复用本 VO。</p>
 *
 * <p>{@code userPhone} 由 Service 层统一经 {@code PhoneMask} 处理后下发，本 VO 绝不承载明文号码；
 * 判据见 {@code WsUserVoSerializationTest}。</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsUserVo", description = "C端用户响应")
public class WsUserVo extends BaseEntityVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    private Long id;

    @Schema(description = "用户名称(max50)")
    private String userName;

    @Schema(description = "性别(20)：1男 2女")
    private Integer userGender;

    @Schema(description = "手机号（已脱敏，前 3 后 4；长度异常整体屏蔽）")
    private String userPhone;

    @Schema(description = "是否禁用(10)：1正常 2禁用")
    private Integer disabledFlag;

    @Schema(description = "用户状态：1正常 2注销")
    private Integer userStatus;

    /**
     * 能力标签，取值与小程序能力投影 {@code MiniCapabilityServiceImpl} 同源：
     * OWNER_VIEW 由名下水站/设备归属行判定，COURIER_WORK 由启用态配送员记录判定。
     * 这里只做「有没有这项能力」的只读投影，不代表任何接口会因此放行动作。
     */
    @Schema(description = "能力标签：OWNER_VIEW 机主 / COURIER_WORK 配送员")
    private List<String> capabilities;

    @Schema(description = "用户头像URL(max500)")
    private String userAvatar;

    @Schema(description = "归属渠道用户ID")
    private Long channelUserId;

    @Schema(description = "推送人用户ID")
    private Long referrerUserId;

    @Schema(description = "注册推送码(max20)")
    private String promoCode;

}

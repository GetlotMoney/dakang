package com.jbk.tool.data.user.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 直接下级行（用户档案「关系归属」分区的分页列表项，只读）。
 *
 * <p>只列直接绑定本人的一级；不提供展开下钻，避免一次点击把整棵关系网拉进浏览器。</p>
 *
 * @author dakang
 * @since 2026-08-13
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsUserRelationItemVo", description = "直接下级行")
public class WsUserRelationItemVo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "姓名")
    private String userName;

    @Schema(description = "手机号（已脱敏）")
    private String userPhone;

    @Schema(description = "注册时间")
    private String createTime;
}

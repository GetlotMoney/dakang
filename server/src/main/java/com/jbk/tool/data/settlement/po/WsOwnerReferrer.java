package com.jbk.tool.data.settlement.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 机主加盟推荐关系（D-404/D-428）：谁把这个机主招进来的，一人一行、建立即冻结（D-406）。
 * 与 ws_user.REFERRER_USER_ID 的用户邀请关系物理分列——那条链回答「喝水的人谁拉来的」，
 * 本表回答「这台机器的机主谁招来的」，D-404 明令不得混用；混用不会报错，只会长期付错人。
 *
 * @author dakang
 * @since 2026-08-14
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_owner_referrer")
@Schema(name = "WsOwnerReferrer", description = "机主加盟推荐关系（一人一行，建立即冻结）")
public class WsOwnerReferrer extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "机主用户ID(ws_user.ID)")
    @TableField("OWNER_USER_ID")
    private Long ownerUserId;

    @Schema(description = "直接推荐人用户ID(ws_user.ID)；任何身份可担任，仅此一级")
    @TableField("REFERRER_USER_ID")
    private Long referrerUserId;

    @Schema(description = "建立来源：ADMIN_ENTRY 后台录入 / INVITE_LINK 邀请链路(预留)")
    @TableField("BIND_SOURCE")
    private String bindSource;

    @Schema(description = "建立时点 yyyyMMddHHmmss；建立即冻结，无换绑入口")
    @TableField("BIND_TIME")
    private String bindTime;

    @Schema(description = "备注")
    @TableField("REFERRER_REMARK")
    private String referrerRemark;
}

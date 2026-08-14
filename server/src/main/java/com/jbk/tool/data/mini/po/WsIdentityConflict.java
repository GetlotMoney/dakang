package com.jbk.tool.data.mini.po;

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
 * 账号身份冲突台账（WX-ECO S1）：fail-closed 拒绝之后的可审计人工处理面。
 * 不是 outbox（无 Worker/租约/重试），冲突需被人逐条裁决。
 * 不存 openid、手机号只存脱敏值：身份密钥复制进第二张表只扩大泄漏面。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("ws_identity_conflict")
@Schema(name = "WsIdentityConflict", description = "账号身份冲突台账")
public class WsIdentityConflict extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "幂等键 IDC:<类型>:<持有方>:<发起方|0>:<脱敏号>")
    @TableField("CONFLICT_KEY")
    private String conflictKey;

    @Schema(description = "冲突类型，见 MiniIdentityConflictEnum.Type")
    @TableField("CONFLICT_TYPE")
    private String conflictType;

    @Schema(description = "发生场景：LOGIN_BIND / SELF_BIND")
    @TableField("OCCUR_SCENE")
    private String occurScene;

    @Schema(description = "当前持有该身份的账号ID")
    @TableField("HOLDER_USER_ID")
    private Long holderUserId;

    @Schema(description = "发起动作的账号ID；票据路径建号前为 0 哨兵")
    @TableField("ACTOR_USER_ID")
    private Long actorUserId;

    @Schema(description = "脱敏手机号，仅供人工核对")
    @TableField("MASKED_PHONE")
    private String maskedPhone;

    @Schema(description = "同一冲突累计发生次数")
    @TableField("OCCUR_COUNT")
    private Integer occurCount;

    @Schema(description = "首次发生时间")
    @TableField("FIRST_OCCUR_TIME")
    private String firstOccurTime;

    @Schema(description = "最近发生时间")
    @TableField("LAST_OCCUR_TIME")
    private String lastOccurTime;

    @Schema(description = "处理状态：1待处理 2已处理 3已忽略")
    @TableField("HANDLE_STATUS")
    private Integer handleStatus;

    @Schema(description = "处理人")
    @TableField("HANDLE_BY")
    private Long handleBy;

    @Schema(description = "处理时间")
    @TableField("HANDLE_TIME")
    private String handleTime;

    @Schema(description = "处理说明")
    @TableField("HANDLE_REMARK")
    private String handleRemark;
}

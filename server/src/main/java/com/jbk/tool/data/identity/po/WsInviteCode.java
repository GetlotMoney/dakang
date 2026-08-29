package com.jbk.tool.data.identity.po;

import com.baomidou.mybatisplus.annotation.*;
import com.jbk.tool.data.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("ws_invite_code")
public class WsInviteCode extends BaseEntity {
    @TableId(value = "ID", type = IdType.AUTO) private Long id;
    @TableField("INVITE_CODE") private String inviteCode;
    @TableField("INVITER_PROFILE_ID") private Long inviterProfileId;
    @TableField("TARGET_CAPABILITY_TYPE") private Integer targetCapabilityType;
    @TableField("REGION_NAME") private String regionName;
    @TableField("TARGET_AGENT_LEVEL") private Integer targetAgentLevel;
    @TableField("USE_LIMIT") private Integer useLimit;
    @TableField("USED_COUNT") private Integer usedCount;
    @TableField("EXPIRE_TIME") private String expireTime;
    @TableField("CODE_STATUS") private Integer codeStatus;
}

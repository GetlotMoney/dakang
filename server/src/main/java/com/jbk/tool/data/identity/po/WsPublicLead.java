package com.jbk.tool.data.identity.po;

import com.baomidou.mybatisplus.annotation.*;
import com.jbk.tool.data.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("ws_public_lead")
public class WsPublicLead extends BaseEntity {
    @TableId(value = "ID", type = IdType.AUTO) private Long id;
    @TableField("OWNER_USER_ID") private Long ownerUserId;
    @TableField("APPLICATION_ID") private Long applicationId;
    @TableField("REGION_NAME") private String regionName;
    @TableField("ASSIGNED_PROFILE_ID") private Long assignedProfileId;
    @TableField("LEAD_STATUS") private Integer leadStatus;
    @TableField("ASSIGNED_TIME") private String assignedTime;
    @TableField("RESPONSE_DEADLINE") private String responseDeadline;
    @TableField("CONFIRMED_TIME") private String confirmedTime;
    @TableField("ASSIGN_ROUND") private Integer assignRound;
}

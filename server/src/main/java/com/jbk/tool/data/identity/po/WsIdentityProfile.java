package com.jbk.tool.data.identity.po;

import com.baomidou.mybatisplus.annotation.*;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("ws_identity_profile")
@Schema(name = "WsIdentityProfile", description = "机主、渠道或区域代理经营主体")
public class WsIdentityProfile extends BaseEntity {
    @TableId(value = "ID", type = IdType.AUTO) private Long id;
    @TableField("USER_ID") private Long userId;
    @TableField("CAPABILITY_TYPE") private Integer capabilityType;
    @TableField("PROFILE_STATUS") private Integer profileStatus;
    @TableField("SUBJECT_TYPE") private Integer subjectType;
    @TableField("SUBJECT_NAME") private String subjectName;
    @TableField("REGION_NAME") private String regionName;
    @TableField("AGENT_LEVEL") private Integer agentLevel;
    @TableField("PARENT_PROFILE_ID") private Long parentProfileId;
    @TableField("APPLICATION_ID") private Long applicationId;
}

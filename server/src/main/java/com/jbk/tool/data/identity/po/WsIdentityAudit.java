package com.jbk.tool.data.identity.po;

import com.baomidou.mybatisplus.annotation.*;
import com.jbk.tool.data.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("ws_identity_audit")
public class WsIdentityAudit extends BaseEntity {
    @TableId(value = "ID", type = IdType.AUTO) private Long id;
    @TableField("APPLICATION_ID") private Long applicationId;
    @TableField("FROM_STATUS") private Integer fromStatus;
    @TableField("TO_STATUS") private Integer toStatus;
    @TableField("ACTOR_TYPE") private Integer actorType;
    @TableField("ACTOR_USER_ID") private Long actorUserId;
    @TableField("AUDIT_REMARK") private String auditRemark;
}

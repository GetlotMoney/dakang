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
@TableName("ws_identity_application")
@Schema(name = "WsIdentityApplication", description = "小程序身份能力申请")
public class WsIdentityApplication extends BaseEntity {
    @TableId(value = "ID", type = IdType.AUTO) private Long id;
    @TableField("USER_ID") private Long userId;
    @TableField("CAPABILITY_TYPE") private Integer capabilityType;
    @TableField("APPLICATION_STATUS") private Integer applicationStatus;
    @TableField("SUBJECT_TYPE") private Integer subjectType;
    @TableField("APPLICANT_NAME") private String applicantName;
    @TableField("BOUND_PHONE") private String boundPhone;
    @TableField("REGION_NAME") private String regionName;
    @TableField("AGENT_LEVEL") private Integer agentLevel;
    @TableField("INVITE_CODE") private String inviteCode;
    @TableField("STATION_NAME") private String stationName;
    @TableField("STATION_ADDRESS") private String stationAddress;
    @TableField("FORM_JSON") private String formJson;
    @TableField("REQUEST_ID") private String requestId;
    @TableField("REVIEW_MODE") private Integer reviewMode;
    @TableField("SUBMITTED_TIME") private String submittedTime;
    @TableField("REVIEWED_TIME") private String reviewedTime;
    @TableField("REVIEWER_USER_ID") private Long reviewerUserId;
    @TableField("REVIEW_REMARK") private String reviewRemark;
}

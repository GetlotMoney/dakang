package com.jbk.tool.data.identity.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(name = "MiniIdentityApplyBo", description = "小程序经营身份申请")
public class MiniIdentityApplyBo {
    @NotNull private Integer capabilityType;
    @NotNull private Integer subjectType;
    @NotBlank @Size(max = 100) private String applicantName;
    @NotBlank @Size(max = 50) private String regionName;
    private Integer agentLevel;
    @Size(max = 50) private String inviteCode;
    @Size(max = 50) private String stationName;
    @Size(max = 200) private String stationAddress;
    @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$") private String requestId;
    @AssertTrue(message = "请先同意身份合作与业务规范") private Boolean declarationAccepted;
}

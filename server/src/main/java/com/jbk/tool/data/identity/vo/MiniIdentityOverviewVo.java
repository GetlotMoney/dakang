package com.jbk.tool.data.identity.vo;

import lombok.Data;
import lombok.experimental.Accessors;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class MiniIdentityOverviewVo {
    private boolean demoMode;
    private List<Role> roles = new ArrayList<>();

    @Data
    @Accessors(chain = true)
    public static class Role {
        private Integer capabilityType;
        private String title;
        private Integer status;
        private String statusText;
        private String capabilityCode;
        private String entryRouteId;
        private String applicantName;
        private String regionName;
        private String submittedTime;
        private String reviewRemark;
    }
}

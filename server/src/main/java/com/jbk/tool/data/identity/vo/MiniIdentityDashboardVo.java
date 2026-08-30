package com.jbk.tool.data.identity.vo;

import com.jbk.tool.data.mini.vo.MiniWalletVo;
import lombok.Data;
import lombok.experimental.Accessors;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class MiniIdentityDashboardVo {
    private Integer capabilityType;
    private String subjectName;
    private String regionName;
    private Integer agentLevel;
    private Long directOwnerCount;
    private Long stationCount;
    private Long orderCount;
    private MiniWalletVo wallet;
    private List<Invite> inviteCodes = new ArrayList<>();
    private List<Owner> owners = new ArrayList<>();
    private List<Lead> publicLeads = new ArrayList<>();
    private List<Node> lineage = new ArrayList<>();

    @Data @Accessors(chain = true)
    public static class Invite {
        private String code;
        private Integer targetCapabilityType;
        private Integer targetAgentLevel;
        private String regionName;
        private Integer usedCount;
    }
    @Data @Accessors(chain = true)
    public static class Owner {
        private String userId;
        private String userName;
        private Long stationCount;
        private String bindTime;
    }
    @Data @Accessors(chain = true)
    public static class Lead {
        private String leadId;
        private String ownerName;
        private String regionName;
        private Integer status;
        private String responseDeadline;
    }
    @Data @Accessors(chain = true)
    public static class Node {
        private String profileId;
        private String subjectName;
        private Integer level;
        private String regionName;
        private boolean current;
    }
}

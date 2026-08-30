package com.jbk.tool.consts.identity;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 小程序经营身份、申请、血缘与演示终态枚举。 */
public interface IdentityEnum {

    @Getter
    @AllArgsConstructor
    enum CapabilityType {
        COURIER(1, "配送员", "COURIER_WORK"),
        OWNER(2, "机主", "OWNER_VIEW"),
        CHANNEL(3, "渠道推广", "CHANNEL_VIEW"),
        REGION(4, "区域代理", "REGION_VIEW");
        private final int value;
        private final String desc;
        private final String capabilityCode;

        public static CapabilityType of(int value) {
            for (CapabilityType item : values()) {
                if (item.value == value) return item;
            }
            throw new IllegalArgumentException("身份能力类型不存在");
        }
    }

    @Getter
    @AllArgsConstructor
    enum ApplicationStatus {
        PENDING(1, "待审核"), APPROVED(2, "已通过"), SUSPENDED(3, "已暂停"), REJECTED(4, "已驳回");
        private final int value;
        private final String desc;
    }

    @Getter
    @AllArgsConstructor
    enum ProfileStatus {
        ENABLED(1, "正常"), SUSPENDED(2, "暂停"), REVOKED(3, "撤销");
        private final int value;
        private final String desc;
    }

    @Getter
    @AllArgsConstructor
    enum SubjectType {
        PERSONAL(1, "个人"), ENTERPRISE(2, "企业");
        private final int value;
        private final String desc;
    }

    @Getter
    @AllArgsConstructor
    enum AgentLevel {
        PROVINCE(1, "省级"), CITY(2, "市级"), COUNTY(3, "区县级");
        private final int value;
        private final String desc;
    }

    @Getter
    @AllArgsConstructor
    enum LeadStatus {
        UNASSIGNED(1, "待分配"), ASSIGNED(2, "待确认"), CONFIRMED(3, "已确认"), EXPIRED(4, "已超时");
        private final int value;
        private final String desc;
    }

    @Getter
    @AllArgsConstructor
    enum WithdrawStatus {
        PROCESSING(1, "处理中"), PAID(2, "已打款"), FAILED(3, "失败已解冻");
        private final int value;
        private final String desc;
    }
}

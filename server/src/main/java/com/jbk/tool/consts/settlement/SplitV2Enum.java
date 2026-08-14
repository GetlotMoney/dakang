package com.jbk.tool.consts.settlement;

import java.util.Arrays;
import java.util.Optional;

/**
 * 分润 V2 枚举（E2E-08 S1，2026-08-06 甲方会议口径）。V1 的 {@link SettlementEnum} 继续服务
 * ws_split_record 与历史数据，本枚举只服务 V2 计划模型；刻意不复用 V1 CHANNEL/REGION（渠道商=省市区县服务商，M11）。
 * 存储用自描述字符串码而非 tinyint+字典；S2 接页面需下拉时再补字典，只加不改。
 *
 * @author dakang
 * @since 2026-08-06
 */
public interface SplitV2Enum {

    /**
     * 分润基数线（M3）：售水与配送费是两条独立基数，绝不合并。
     * 陷阱：V1 ProductLine.DELIVERY 装的是「水费+配送费」整单金额（任务书偏差1），V2 新码值防历史行混读。
     */
    enum BasisLine {
        /** 水费分润线：基数=优惠减免后的实收水费（M4 实收分润） */
        WATER_SALE,
        /** 配送服务费分润线：主要归配送员，平台是否参与由计划配置（M5） */
        DELIVERY_FEE;

        public static Optional<BasisLine> of(String code) {
            return Arrays.stream(values()).filter(v -> v.name().equals(code)).findFirst();
        }
    }

    /**
     * 分润角色编码（会议身份模型：一人可同时具备多角色，各角色分别计算，M1/M2）。
     */
    enum RoleCode {
        /** 机主：基础流水收益，独立于其他角色 */
        WATER_OWNER,
        /** 机主的一级直接推荐人（M6）：只此一级，任何身份可担任；不是 ws_user.REFERRER_USER_ID 的用户邀请关系（M10）。 */
        WATER_DIRECT_REFERRER,
        /** 省级区域服务商（级差模型，M7） */
        REGION_PROVINCE,
        /** 市级区域服务商 */
        REGION_CITY,
        /** 区县级区域服务商 */
        REGION_COUNTY,
        /** 配送员：配送费线的主收益方（M5） */
        DELIVERY_COURIER,
        /** 平台余数行：非平台组件向下取整后的剩余，恒等式的封口（工程规则 7） */
        PLATFORM_REMAINDER;

        public static Optional<RoleCode> of(String code) {
            return Arrays.stream(values()).filter(v -> v.name().equals(code)).findFirst();
        }
    }

    /** 计划项的区域层级；非区域角色恒为 NONE。 */
    enum RegionLevel {
        NONE,
        PROVINCE,
        CITY,
        COUNTY
    }

    /**
     * 比例模式。FIXED：rateBp 即直接比例；REGIONAL_CUMULATIVE：rateBp 是该层级累计权益上限（M7），
     * 实得=自身累计-最近下级累计，绝不把三层比例相加。
     */
    enum RateMode {
        FIXED,
        REGIONAL_CUMULATIVE
    }

    /**
     * 订单归属来源（M8/M9）。PRIVATE_REFERRAL：私域血缘链，建立时冻结，跨省放机不改归属（M8）；
     * PUBLIC_UNASSIGNED：公域未分配，推荐人/区域组件不生成，份额归平台（M9）；
     * PUBLIC_MANUAL：公域人工分配链，自动分配本轮禁止实现（M9）。
     */
    enum AttributionSource {
        PRIVATE_REFERRAL,
        PUBLIC_UNASSIGNED,
        PUBLIC_MANUAL
    }

    /** 计划状态：草稿可改，生效不可改（改比例=发新版本），停用后不再被选取。 */
    enum PlanStatus {
        DRAFT(1, "草稿"),
        ACTIVE(2, "生效"),
        RETIRED(3, "停用");

        private final int value;
        private final String desc;

        PlanStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }
    }
}

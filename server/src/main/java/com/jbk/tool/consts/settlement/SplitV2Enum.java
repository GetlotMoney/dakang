package com.jbk.tool.consts.settlement;

import java.util.Arrays;
import java.util.Optional;

/**
 * 分润 V2 枚举（E2E-08 S1，2026-08-06 甲方会议口径）。
 *
 * <p>与 {@link SettlementEnum} 的关系：V1 的 ProductLine/ReceiverType 继续服务既有
 * {@code ws_split_record} 状态机与历史数据，本枚举只服务 V2 计划模型与纯计算器。
 * 刻意<b>不</b>复用 V1 的 CHANNEL/REGION 两个并列预留位——会议已明确
 * 「渠道商就是省市区县服务商」（M11），一人一单可multiple角色，V2 用互斥且完备的
 * 角色编码表达，而不是含义重叠的收款方类型。</p>
 *
 * <p>存储形态用字符串码而非 tinyint+字典：本轮无任何页面消费（第八节禁 UI），
 * 计算证据表的首要读者是排障与对账的人，自描述字符串省掉一次字典对照；
 * S2 接页面时若需要下拉再补字典，届时只加不改。</p>
 *
 * @author dakang
 * @since 2026-08-06
 */
public interface SplitV2Enum {

    /**
     * 分润基数线（M3）：售水与配送费是两条独立基数，绝不合并。
     *
     * <p>V1 的 {@code ProductLine.DELIVERY} 实际装的是「水费+配送费」整单金额
     * （任务书偏差 1），V2 起新码值与 V1 区分，避免历史行与新组件混读。</p>
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
        /** 机主：基础流水收益，独立于其他角色（会议 00:18「基础的收益是独立的享受流水分成」） */
        WATER_OWNER,
        /**
         * 机主的一级直接推荐人（M6）：只此一级，任何身份都可担任
         * （会议 26:53「不管什么身份都可以推荐加盟商」）。
         * <b>不是</b> {@code ws_user.REFERRER_USER_ID} 的用户邀请关系（M10）。
         */
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
     * 比例模式。
     *
     * <p>FIXED：rateBp 即该角色的直接比例。<br>
     * REGIONAL_CUMULATIVE：rateBp 是该层级的<b>累计权益上限</b>（M7），
     * 实得=自身累计-最近下级累计，由计算器求级差，绝不把三层比例相加。</p>
     */
    enum RateMode {
        FIXED,
        REGIONAL_CUMULATIVE
    }

    /**
     * 订单归属来源（M8/M9）。
     *
     * <p>PRIVATE_REFERRAL：私域血缘链，链在建立时冻结，跨省放机器不改归属（M8）。<br>
     * PUBLIC_UNASSIGNED：公域且未分配，推荐人/区域组件一律不生成，份额归平台（M9）。<br>
     * PUBLIC_MANUAL：公域经后台人工分配出的链——会议明确只允许手动分配，
     * 「优先分给最近最低层级」只是运营倾向，自动分配本轮禁止实现（M9）。</p>
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

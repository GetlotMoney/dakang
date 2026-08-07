package com.jbk.tool.consts.delivery;

import com.jbk.tool.exception.JbkException;

import java.util.Arrays;
import java.util.List;

/**
 * 配送域枚举（E2E-03 包A；值对齐 02-ws-business.sql 字典 1351/1352/1353/1354/1355）。
 * <p>状态机与小程序 Mock 契约（miniapp/src/api/delivery.ts）语义一致：
 * 待接单→已接单→配送中→已送达待确认→已签收；签收后申诉转 7 申诉中。</p>
 *
 * @author dakang
 * @since 2026-07-23
 */
public interface DeliveryEnum {

    /** 配送任务状态 (dictType=1351) */
    enum TaskStatus {
        PENDING(1, "待接单"),
        ACCEPTED(2, "已接单"),
        DELIVERING(3, "配送中"),
        ARRIVED(4, "已送达待确认"),
        SIGNED(5, "已签收"),
        CANCELLED(6, "已取消"),
        APPEALING(7, "申诉中");

        private final int value;
        private final String desc;

        TaskStatus(int value, String desc) {
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

    /** 申诉状态 (dictType=1352)：裁决只允许 3/5/2 三种确定结果（E2E-03 规则17） */
    enum AppealStatus {
        PENDING(1, "待处理"),
        COMPENSATE_PENDING(2, "成立待补偿"),
        REJECTED(3, "不成立驳回"),
        WITHDRAWN(4, "撤销"),
        RESEND_PENDING(5, "补送待执行");

        private final int value;
        private final String desc;

        AppealStatus(int value, String desc) {
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

    /** 签收定位记录状态 (dictType=1353)：声明「已记录」时三照必须携带合法坐标，禁止文案超出证据 */
    enum LocationStatus {
        RECORDED(1, "定位已记录"),
        UNRECORDED(2, "定位未记录");

        private final int value;
        private final String desc;

        LocationStatus(int value, String desc) {
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

    /** 配送异常原因 (dictType=1354) */
    enum ExceptionReason {
        UNREACHABLE(1, "联系不上用户"),
        ADDRESS(2, "地址异常"),
        QUANTITY(3, "数量问题"),
        DAMAGED(4, "货物破损"),
        OTHER(5, "其他");

        private final int value;
        private final String desc;

        ExceptionReason(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static ExceptionReason getType(Integer type) {
            if (type == null) {
                throw new JbkException("配送异常原因不能为空");
            }
            for (ExceptionReason item : ExceptionReason.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            // fail-closed：未登记原因一律拒绝，防脏值绕过字典口径
            throw new JbkException("配送异常原因不合法");
        }
    }

    /** 自动补货规则状态 (dictType=1355) */
    enum AutoRuleStatus {
        ENABLED(1, "启用"),
        DISABLED(2, "停用");

        private final int value;
        private final String desc;

        AutoRuleStatus(int value, String desc) {
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

    /** 受控媒体用途（ws_delivery_media.MEDIA_PURPOSE；跨用途引用一律拒绝） */
    enum MediaPurpose {
        SIGN_PHOTO(1, "签收三照"),
        APPEAL_EVIDENCE(2, "申诉举证"),
        EXCEPTION_EVIDENCE(3, "异常举证"),
        WORK_ORDER(4, "工单证据");

        private final int value;
        private final String desc;

        MediaPurpose(int value, String desc) {
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

    /** 申诉原因码（ws_delivery_appeal.APPEAL_REASON，对齐小程序契约枚举；白名单外一律拒绝） */
    List<String> APPEAL_REASON_CODES = Arrays.asList("QUANTITY", "QUALITY", "DAMAGE", "PLACEMENT", "OTHER");

    /** 签收三照类型：1门牌 2水品 3摆放，缺一不可（E2E-03 规则12） */
    List<Integer> SIGN_PHOTO_TYPES = Arrays.asList(1, 2, 3);
}

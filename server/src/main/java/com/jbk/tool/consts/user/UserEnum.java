package com.jbk.tool.consts.user;

import com.jbk.tool.exception.JbkException;

/**
 * 用户域枚举（水卡 / 成员授权 / 配送员准入）
 * <p>
 * 字典编号已在 {@link com.jbk.tool.consts.ApiEnum.DictType} 注册：
 * 1331 水卡类型、1332 水卡状态、1333 成员授权状态、1350 配送员状态；
 * 字典数据由 ws_user_card.sql / ws_delivery.sql 落库。
 * </p>
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface UserEnum {

    // 水卡类型 (dictType=1331)
    enum CardType {
        VIRTUAL(1, "虚拟卡"),
        ENTITY(2, "实体卡");

        private final int value;
        private final String desc;

        CardType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static CardType getType(int type) {
            for (CardType item : CardType.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("水卡类型不存在");
        }
    }

    // 水卡状态 (dictType=1332)
    enum CardStatus {
        NORMAL(1, "正常"),
        FROZEN(2, "冻结"),
        EXPIRED(3, "已过期"),
        CANCELLED(4, "已注销");

        private final int value;
        private final String desc;

        CardStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static CardStatus getType(int type) {
            for (CardStatus item : CardStatus.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("水卡状态不存在");
        }
    }

    // 成员授权状态 (dictType=1333)
    enum CardMemberStatus {
        ACTIVE(1, "生效"),
        REMOVED(2, "已解除");

        private final int value;
        private final String desc;

        CardMemberStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static CardMemberStatus getType(int type) {
            for (CardMemberStatus item : CardMemberStatus.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("成员授权状态不存在");
        }
    }

    // 配送员状态 (dictType=1350)
    enum CourierStatus {
        PENDING(1, "待审核"),
        ENABLED(2, "启用"),
        DISABLED(3, "停用"),
        REJECTED(4, "审核驳回");

        private final int value;
        private final String desc;

        CourierStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static CourierStatus getType(int type) {
            for (CourierStatus item : CourierStatus.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("配送员状态不存在");
        }
    }
}

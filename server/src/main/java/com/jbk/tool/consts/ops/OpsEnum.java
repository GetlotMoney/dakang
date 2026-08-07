package com.jbk.tool.consts.ops;

import com.jbk.tool.exception.JbkException;

/**
 * 运维事件域枚举
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface OpsEnum {

    // 领域事件类型 (dictType=1363)
    enum EventType {
        ORDER_STATUS(1, "订单状态变化"),
        DEVICE_STATUS(2, "设备状态变化"),
        ALARM_CREATED(3, "告警产生"),
        WORK_ORDER_STATUS(4, "工单状态变化"),
        DELIVERY_NODE(5, "配送节点变化"),
        PAYMENT_RESULT(6, "支付结果"),
        SPLIT_RESULT(7, "分账结果"),
        COMMAND_STATUS(8, "指令状态变化"),
        /**
         * 售后动作（E2E-04 包A）：字典 1363 的值 9 已由
         * deploy/mysql/migrations/2026-07-29-aftersale-e2e04-a.sql 写入，此处补齐 Java 侧登记。
         * 售后返还的成功审计与落痕落空证据都用本类型，不再挤占 ORDER_STATUS。
         */
        AFTER_SALE(9, "售后动作"),

        /**
         * 告警状态变化（E2E-05 包B）：忽略/自动恢复/转工单的处置轨迹。
         * ALARM_CREATED(3) 只表达产生；处置挤在它名下会让「告警产生」事件流里混进恢复记录，
         * 按类型检索的运维报表随之失真。字典 1363#10 由 02-ws-business.sql 与包A 迁移同步写入。
         */
        ALARM_RECOVERED(10, "告警状态变化");

        private final int value;
        private final String desc;

        EventType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static EventType getType(int type) {
            for (EventType item : EventType.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 告警类型 (dictType=1360)
    enum AlarmType {
        DEVICE_OFFLINE(1, "设备离线"),
        FAULT_CODE(2, "故障码"),
        TDS_OVER(3, "TDS超标"),
        FILTER_EXPIRE(4, "滤芯超时"),
        SIM_ABNORMAL(5, "SIM异常"),
        COMMAND_TIMEOUT(6, "指令超时"),
        DISPENSE_ABNORMAL(7, "出水异常");

        private final int value;
        private final String desc;

        AlarmType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static AlarmType getType(int type) {
            for (AlarmType item : AlarmType.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 告警状态 (dictType=1361)
    enum AlarmStatus {
        PENDING(1, "待处理"),
        TO_WORK_ORDER(2, "已转工单"),
        IGNORED(3, "已忽略"),
        AUTO_RECOVERED(4, "自动恢复");

        private final int value;
        private final String desc;

        AlarmStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static AlarmStatus getType(int type) {
            for (AlarmStatus item : AlarmStatus.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 工单类型 (dictType=1365)
    enum WorkOrderType {
        REPAIR(1, "维修"),
        PARTS(2, "配件"),
        INSPECTION(3, "巡检");

        private final int value;
        private final String desc;

        WorkOrderType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static WorkOrderType getType(int type) {
            for (WorkOrderType item : WorkOrderType.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 工单来源
    enum WorkOrderSource {
        FROM_ALARM(1, "告警转入"),
        OWNER_APPLY(2, "机主申报"),
        CONSOLE(3, "后台创建");

        private final int value;
        private final String desc;

        WorkOrderSource(int value, String desc) {
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

    // 工单状态 (dictType=1362，E2E-05 六状态)
    enum WorkOrderStatus {
        WAIT_CONFIRM(1, "待确认"),
        WAIT_ASSIGN(2, "待分配"),
        PROCESSING(3, "处理中"),
        WAIT_REVIEW(4, "待复核"),
        CLOSED(5, "已关闭"),
        REJECTED(6, "已驳回");

        private final int value;
        private final String desc;

        WorkOrderStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static WorkOrderStatus getType(int type) {
            for (WorkOrderStatus item : WorkOrderStatus.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 操作端口 (dictType=1364)
    enum ActorPortal {
        MANAGE(1, "公司后台"),
        USER(2, "用户端"),
        OWNER(3, "机主端"),
        COURIER(4, "配送端"),
        CHANNEL(5, "渠道端"),
        SYSTEM(6, "系统"),
        DEVICE(7, "设备");

        private final int value;
        private final String desc;

        ActorPortal(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static ActorPortal getType(int type) {
            for (ActorPortal item : ActorPortal.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }
}

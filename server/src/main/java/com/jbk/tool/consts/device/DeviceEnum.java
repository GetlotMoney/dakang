package com.jbk.tool.consts.device;

import com.jbk.tool.exception.JbkException;

/**
 * 设备域枚举
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface DeviceEnum {

    // 设备在线状态 (dictType=1300)
    enum OnlineStatus {
        ONLINE(1, "在线"),
        OFFLINE(2, "离线"),
        INACTIVE(3, "未激活");

        private final int value;
        private final String desc;

        OnlineStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static OnlineStatus getType(int type) {
            for (OnlineStatus item : OnlineStatus.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 设备运行状态 (dictType=1301)
    enum RunStatus {
        IDLE(1, "空闲"),
        DISPENSING(2, "出水中"),
        FAULT(3, "故障"),
        MAINTAIN(4, "维护中"),
        LOCKED(5, "锁机");

        private final int value;
        private final String desc;

        RunStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static RunStatus getType(int type) {
            for (RunStatus item : RunStatus.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // SIM 状态 (dictType=1368)。真实运营商查询未接入，当前值来自运营档案或模拟环境。
    enum SimStatus {
        NORMAL(1, "正常"),
        INACTIVE(2, "未激活"),
        ARREARS(3, "欠费"),
        DISABLED(4, "停用");

        private final int value;
        private final String desc;

        SimStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static SimStatus getType(int type) {
            for (SimStatus item : SimStatus.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("SIM状态不存在");
        }
    }

    // 上行消息类型 (dictType=1310)
    enum MsgType {
        STATUS(1, "状态上报"),
        ACK(2, "指令回执"),
        RESULT(3, "水量回传"),
        FAULT(4, "故障事件"),
        REPLAY(5, "补传消息");

        private final int value;
        private final String desc;

        MsgType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static MsgType getType(int type) {
            for (MsgType item : MsgType.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 消息处理状态 (dictType=1311)
    enum MsgHandleStatus {
        PENDING(1, "待处理"),
        HANDLED(2, "已处理"),
        FAILED(3, "处理失败"),
        DUPLICATED(4, "重复丢弃");

        private final int value;
        private final String desc;

        MsgHandleStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static MsgHandleStatus getType(int type) {
            for (MsgHandleStatus item : MsgHandleStatus.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 设备指令类型 (dictType=1320)
    enum CmdType {
        START_DISPENSE(1, "开始出水"),
        STOP_DISPENSE(2, "停止出水"),
        QUERY_STATUS(3, "查询状态"),
        LOCK(4, "锁机"),
        UNLOCK(5, "解锁"),
        PARAM_SYNC(6, "参数同步"),
        REBOOT(7, "重启"),
        PRICE_SYNC(8, "价格同步");

        private final int value;
        private final String desc;

        CmdType(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static CmdType getType(int type) {
            for (CmdType item : CmdType.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }

    // 设备指令状态 (dictType=1321)
    enum CmdStatus {
        PENDING(1, "待下发"),
        SENT(2, "已下发"),
        ACKED(3, "已回执"),
        SUCCESS(4, "执行成功"),
        FAILED(5, "执行失败"),
        TIMEOUT(6, "超时"),
        PARTIAL(7, "部分完成");

        private final int value;
        private final String desc;

        CmdStatus(int value, String desc) {
            this.value = value;
            this.desc = desc;
        }

        public int getValue() {
            return value;
        }

        public String getDesc() {
            return desc;
        }

        public static CmdStatus getType(int type) {
            for (CmdStatus item : CmdStatus.values()) {
                if (item.getValue() == type) {
                    return item;
                }
            }
            throw new JbkException("类型不存在");
        }
    }
}

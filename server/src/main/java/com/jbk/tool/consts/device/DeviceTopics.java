package com.jbk.tool.consts.device;

/**
 * 设备 MQTT 主题规则（协议 v0.1 待硬件厂确认，详见 docs/mqtt-topics.md）。
 * 上行：up/{deviceNo}/{kind}；下行：down/{deviceNo}/cmd。
 */
public final class DeviceTopics {

    private DeviceTopics() {
    }

    /** 上行通配订阅：所有设备所有类型 */
    public static final String UP_ALL = "up/+/+";

    /** 上行类型：心跳（在线判定依据） */
    public static final String KIND_HEARTBEAT = "heartbeat";
    /** 上行类型：运行状态/故障上报（协议文档 up/{deviceNo}/status） */
    public static final String KIND_STATUS = "status";
    /** 上行类型：遥测（TDS/水温/滤芯/信号） */
    public static final String KIND_TELEMETRY = "telemetry";
    /** 上行类型：指令回执（设备已收到） */
    public static final String KIND_ACK = "ack";
    /** 上行类型：指令执行结果（成功/失败/实际水量） */
    public static final String KIND_RESULT = "result";
    /** 上行类型：断网补传（载荷为原 status/ack/result 消息，带原 msgId） */
    public static final String KIND_REPLAY = "replay";

    /** 下行指令主题 */
    public static String cmdTopic(String deviceNo) {
        return "down/" + deviceNo + "/cmd";
    }

    /** 从上行主题解析 deviceNo（形如 up/DK-DEV-0001/heartbeat），非法主题返回 null */
    public static String parseDeviceNo(String topic) {
        String[] parts = topic == null ? null : topic.split("/");
        return parts != null && parts.length == 3 && "up".equals(parts[0]) ? parts[1] : null;
    }

    /** 从上行主题解析消息类型，非法主题返回 null */
    public static String parseKind(String topic) {
        String[] parts = topic == null ? null : topic.split("/");
        return parts != null && parts.length == 3 && "up".equals(parts[0]) ? parts[2] : null;
    }
}

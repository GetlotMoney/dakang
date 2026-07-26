package com.jbk.serve.device;

/**
 * 设备上行消息处理入口（骨架）。
 * MqttConnectionManager 收到任何 up/{deviceNo}/{kind} 消息后回调本接口，
 * 设备接入模块开发时由实现类完成：MSG_ID 去重落 ws_device_msg → 按 kind 分发
 * （心跳刷在线状态 / 遥测落 ws_device_telemetry / 回执与结果推进 ws_command 状态机）。
 */
public interface DeviceUplinkHandler {

    /**
     * @param deviceNo 设备编号（主题第二段）
     * @param kind     消息类型（主题第三段：heartbeat/telemetry/event/ack/result）
     * @param payload  消息原文 JSON
     */
    void onUplink(String deviceNo, String kind, String payload);
}

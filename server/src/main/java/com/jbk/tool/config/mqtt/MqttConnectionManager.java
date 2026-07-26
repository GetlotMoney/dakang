package com.jbk.tool.config.mqtt;

import com.jbk.serve.device.DeviceUplinkHandler;
import com.jbk.tool.consts.device.DeviceTopics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.nio.charset.StandardCharsets;

/**
 * MQTT 连接管理（EMQX）：应用就绪后连接 Broker，订阅设备上行通配主题，
 * 收到消息回调 DeviceUplinkHandler；对外提供指令下行发布。
 * QoS 统一为 1（至少一次），业务层通过 MSG_ID / COMMAND_NO 唯一索引保证幂等性。
 */
@Slf4j
public class MqttConnectionManager {

    private final MqttProperties properties;
    private final ObjectProvider<DeviceUplinkHandler> handlerProvider;
    private MqttClient client;

    public MqttConnectionManager(MqttProperties properties, ObjectProvider<DeviceUplinkHandler> handlerProvider) {
        this.properties = properties;
        this.handlerProvider = handlerProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void connect() {
        try {
            client = new MqttClient(properties.getBrokerUrl(), properties.getClientId(), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(properties.getUsername());
            options.setPassword(properties.getPassword() == null ? new char[0] : properties.getPassword().toCharArray());
            options.setConnectionTimeout(properties.getConnectionTimeout());
            options.setKeepAliveInterval(properties.getKeepAliveInterval());
            // 启用自动重连，并在 connectComplete 回调中恢复主题订阅。
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);

            client.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    log.info("MQTT {} 连接成功：{}", reconnect ? "重" : "首次", serverURI);
                    subscribeUplink();
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("MQTT 连接断开，等待自动重连", cause);
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    dispatch(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // QoS1 发布完成，无需处理
                }
            });
            client.connect(options);
        } catch (MqttException e) {
            // 初始 Broker 连接失败不阻断管理端启动，客户端将继续执行自动重连。
            log.error("MQTT 初始连接失败：{}", properties.getBrokerUrl(), e);
        }
    }

    private void subscribeUplink() {
        try {
            client.subscribe(DeviceTopics.UP_ALL, 1);
            log.info("MQTT 已订阅设备上行主题：{}", DeviceTopics.UP_ALL);
        } catch (MqttException e) {
            log.error("MQTT 订阅失败：{}", DeviceTopics.UP_ALL, e);
        }
    }

    private void dispatch(String topic, String payload) {
        String deviceNo = DeviceTopics.parseDeviceNo(topic);
        String kind = DeviceTopics.parseKind(topic);
        if (deviceNo == null || kind == null) {
            log.warn("MQTT 收到非法主题消息：{}", topic);
            return;
        }
        DeviceUplinkHandler handler = handlerProvider.getIfAvailable();
        if (handler == null) {
            // 未注册上行处理器时记录消息上下文，不触发业务处理。
            log.info("MQTT 上行（暂无处理器）：device={} kind={} payload={}", deviceNo, kind, payload);
            return;
        }
        try {
            handler.onUplink(deviceNo, kind, payload);
        } catch (Exception e) {
            log.error("MQTT 上行处理异常：topic={}", topic, e);
        }
    }

    /**
     * 指令下行（QoS1）。Broker 未连接时抛出 IllegalStateException，
     * 由调用方（指令服务）将 ws_command 更新为下发失败。
     */
    public void publishCommand(String deviceNo, String payloadJson) {
        if (client == null || !client.isConnected()) {
            throw new IllegalStateException("MQTT 未连接，指令无法下发");
        }
        try {
            MqttMessage message = new MqttMessage(payloadJson.getBytes(StandardCharsets.UTF_8));
            message.setQos(1);
            client.publish(DeviceTopics.cmdTopic(deviceNo), message);
        } catch (MqttException e) {
            throw new IllegalStateException("MQTT 指令发布失败：" + deviceNo, e);
        }
    }

    @PreDestroy
    public void disconnect() {
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
            } catch (MqttException e) {
                log.warn("MQTT 断开连接异常", e);
            }
        }
    }
}

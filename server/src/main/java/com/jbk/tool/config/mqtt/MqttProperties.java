package com.jbk.tool.config.mqtt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQTT 连接配置（EMQX）。
 * 默认关闭（mqtt.enabled=false）。完成 EMQX 部署与账号配置后，在 application-*.yml 中启用；
 * 未部署 EMQX 的管理端联调环境仍可正常启动。
 */
@Data
@ConfigurationProperties(prefix = "mqtt")
public class MqttProperties {

    /** 是否启用 MQTT 设备接入 */
    private boolean enabled = false;

    /** Broker 地址，如 tcp://localhost:1883 */
    private String brokerUrl;

    /** 服务端客户端 ID（集群内唯一） */
    private String clientId;

    private String username;

    private String password;

    /** 连接超时（秒） */
    private int connectionTimeout = 10;

    /** 心跳间隔（秒） */
    private int keepAliveInterval = 30;
}

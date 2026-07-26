package com.jbk.tool.config.mqtt;

import com.jbk.serve.device.DeviceUplinkHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQTT 设备接入装配。mqtt.enabled=true 时生效（默认关闭，见 MqttProperties）。
 */
@Configuration
@EnableConfigurationProperties(MqttProperties.class)
public class MqttConfig {

    @Bean
    @ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true")
    public MqttConnectionManager mqttConnectionManager(MqttProperties properties,
                                                       ObjectProvider<DeviceUplinkHandler> handlerProvider) {
        return new MqttConnectionManager(properties, handlerProvider);
    }
}

package com.goev.mqtt;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

@Configuration
@EnableConfigurationProperties(MqttProperties.class)
public class MqttConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MqttConfig.class);

    private final MqttProperties properties;

    public MqttConfig(MqttProperties properties) {
        this.properties = properties;
    }

    @Bean
    @ConditionalOnProperty(name = "goev.mqtt.enabled", havingValue = "true", matchIfMissing = true)
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{properties.getBrokerUrl()});
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        // Hosted brokers (HiveMQ Cloud, EMQX Cloud) require credentials; local Mosquitto does not.
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            options.setUserName(properties.getUsername());
            options.setPassword(properties.getPassword() == null
                    ? new char[0] : properties.getPassword().toCharArray());
        }
        factory.setConnectionOptions(options);
        return factory;
    }

    @Bean
    public MessageChannel mqttInboundChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    @ConditionalOnProperty(name = "goev.mqtt.enabled", havingValue = "true", matchIfMissing = true)
    public MqttPahoMessageDrivenChannelAdapter mqttInboundAdapter() {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                properties.getClientId() + "-in",
                mqttClientFactory(),
                properties.getEvStatusTopic(),
                properties.getEvRequestTopic(),
                properties.getEvcsStatusTopic(),"evcs/+/ack");
        adapter.setCompletionTimeout(5000);
        adapter.setQos(properties.getQosStateChange());
        adapter.setOutputChannel(mqttInboundChannel());
        return adapter;
    }

    @Bean
    @ConditionalOnProperty(name = "goev.mqtt.enabled", havingValue = "true", matchIfMissing = true)
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler mqttOutboundAdapter() {
        MqttPahoMessageHandler handler = new MqttPahoMessageHandler(
                properties.getClientId() + "-out", mqttClientFactory());
        handler.setAsync(true);
        handler.setDefaultQos(properties.getQosStateChange());
        return handler;
    }

    /**
     * With {@code goev.mqtt.enabled=false} (e.g. a hosted demo without a broker) nothing connects
     * to MQTT; outbound responses/commands are dropped here so routing still works. The dashboard's
     * demo controls feed {@link MqttInboundRouter} directly, so they keep working too.
     */
    @Bean
    @ConditionalOnProperty(name = "goev.mqtt.enabled", havingValue = "false")
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler mqttDisabledOutboundHandler() {
        return message -> log.debug("MQTT disabled; dropping outbound message for topic {}",
                message.getHeaders().get(org.springframework.integration.mqtt.support.MqttHeaders.TOPIC));
    }
}

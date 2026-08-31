package com.goev.mqtt;

import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Publishes outbound assignment responses and station commands over MQTT. */
@Component
public class MqttGateway {

    private final MessageChannel mqttOutboundChannel;
    private final ObjectMapper objectMapper;
    private final MqttProperties properties;

    public MqttGateway(MessageChannel mqttOutboundChannel, ObjectMapper objectMapper, MqttProperties properties) {
        this.mqttOutboundChannel = mqttOutboundChannel;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publishResponse(String evId, AssignmentResponseMessage response) {
        publish("ev/" + evId + "/response", response, properties.getQosStateChange());
    }

    public void publishCommand(String evcsId, EvcsCommandMessage command) {
        publish("evcs/" + evcsId + "/command", command, properties.getQosStateChange());
    }

    private void publish(String topic, Object payload, int qos) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            Message<String> message = MessageBuilder.withPayload(json)
                    .setHeader(MqttHeaders.TOPIC, topic)
                    .setHeader(MqttHeaders.QOS, qos)
                    .build();
            mqttOutboundChannel.send(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MQTT payload for topic " + topic, e);
        }
    }
}

package com.goev.mqtt;

import java.util.List;

import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Deserializes inbound MQTT payloads and dispatches them to whichever handler
 * beans exist for that topic. Handler lists are injected by Spring and are
 * empty until later phases (state service, policy engine) register beans —
 * this class only does topic routing and JSON parsing, no business logic.
 */
@Component
public class MqttInboundRouter {

    private final ObjectMapper objectMapper;
    private final List<EvStatusHandler> evStatusHandlers;
    private final List<ChargeRequestHandler> chargeRequestHandlers;
    private final List<EvcsStatusHandler> evcsStatusHandlers;

    public MqttInboundRouter(ObjectMapper objectMapper,
                              List<EvStatusHandler> evStatusHandlers,
                              List<ChargeRequestHandler> chargeRequestHandlers,
                              List<EvcsStatusHandler> evcsStatusHandlers) {
        this.objectMapper = objectMapper;
        this.evStatusHandlers = evStatusHandlers;
        this.chargeRequestHandlers = chargeRequestHandlers;
        this.evcsStatusHandlers = evcsStatusHandlers;
    }

    @ServiceActivator(inputChannel = "mqttInboundChannel")
    public void handle(Message<String> message) throws Exception {
        String topic = String.valueOf(message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC));
        String[] segments = topic.split("/");
        if (segments.length != 3) {
            throw new IllegalArgumentException("Unrecognized MQTT topic: " + topic);
        }
        String category = segments[0];
        String suffix = segments[2];
        String payload = message.getPayload();

        if ("ev".equals(category) && "status".equals(suffix)) {
            EvStatusMessage parsed = objectMapper.readValue(payload, EvStatusMessage.class);
            evStatusHandlers.forEach(h -> h.onEvStatus(parsed));
        } else if ("ev".equals(category) && "request".equals(suffix)) {
            ChargeRequestMessage parsed = objectMapper.readValue(payload, ChargeRequestMessage.class);
            chargeRequestHandlers.forEach(h -> h.onChargeRequest(parsed));
        } else if ("evcs".equals(category) && "status".equals(suffix)) {
            EvcsStatusMessage parsed = objectMapper.readValue(payload, EvcsStatusMessage.class);
            evcsStatusHandlers.forEach(h -> h.onEvcsStatus(parsed));
        } else {
            throw new IllegalArgumentException("Unrecognized MQTT topic: " + topic);
        }
    }
}

package com.goev.mqtt;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Each handler is isolated: one throwing (e.g. a downstream store being
 * unreachable) is logged and doesn't stop the others in the list from
 * running.
 */
@Component
public class MqttInboundRouter {

    private static final Logger log = LoggerFactory.getLogger(MqttInboundRouter.class);

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
            dispatch(evStatusHandlers, h -> h.onEvStatus(parsed));
        } else if ("ev".equals(category) && "request".equals(suffix)) {
            ChargeRequestMessage parsed = objectMapper.readValue(payload, ChargeRequestMessage.class);
            dispatch(chargeRequestHandlers, h -> h.onChargeRequest(parsed));
        } else if ("evcs".equals(category) && "status".equals(suffix)) {
            EvcsStatusMessage parsed = objectMapper.readValue(payload, EvcsStatusMessage.class);
            dispatch(evcsStatusHandlers, h -> h.onEvcsStatus(parsed));
        } else {
            throw new IllegalArgumentException("Unrecognized MQTT topic: " + topic);
        }
    }

    private <T> void dispatch(List<T> handlers, java.util.function.Consumer<T> invocation) {
        for (T handler : handlers) {
            try {
                invocation.accept(handler);
            } catch (Exception e) {
                log.error("Handler {} failed to process an inbound MQTT message", handler.getClass().getSimpleName(), e);
            }
        }
    }
}

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
 *
 * The three {@code dispatchXxx} methods are public so a message that didn't
 * arrive over MQTT — e.g. the dashboard's manual EVCS-placement/charge-request
 * controls — can still go through the exact same handler fan-out (same
 * auto-registration, live state, and routing side effects a real device's
 * message would trigger) without a browser needing to speak MQTT itself.
 */
@Component
public class MqttInboundRouter {

    private static final Logger log = LoggerFactory.getLogger(MqttInboundRouter.class);

    private final ObjectMapper objectMapper;
    private final List<EvStatusHandler> evStatusHandlers;
    private final List<ChargeRequestHandler> chargeRequestHandlers;
    private final List<EvcsStatusHandler> evcsStatusHandlers;
    private List<ChargingAckHandler> chargingAckHandlers=List.of();
    @org.springframework.beans.factory.annotation.Autowired
    public void setChargingAckHandlers(List<ChargingAckHandler> handlers){this.chargingAckHandlers=handlers;}

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
            var status=objectMapper.readValue(payload, EvStatusMessage.class);
            if(!segments[1].equals(status.evId()))throw new IllegalArgumentException("topic identity mismatch");
            dispatchEvStatus(status);
        } else if ("ev".equals(category) && "request".equals(suffix)) {
            var request=objectMapper.readValue(payload, ChargeRequestMessage.class);
            if(!segments[1].equals(request.evId()))throw new IllegalArgumentException("topic identity mismatch");
            dispatchChargeRequest(request);
        } else if ("evcs".equals(category) && "status".equals(suffix)) {
            var status=objectMapper.readValue(payload, EvcsStatusMessage.class);
            if(!segments[1].equals(status.evcsId()))throw new IllegalArgumentException("topic identity mismatch");
            dispatchEvcsStatus(status);
        } else if("evcs".equals(category) && "ack".equals(suffix)) {
            var ack=objectMapper.readValue(payload,ChargingAckMessage.class);
            if(!segments[1].equals(ack.evcsId()))throw new IllegalArgumentException("ACK topic identity mismatch");
            dispatch(chargingAckHandlers,h->h.onAck(ack));
        } else {
            throw new IllegalArgumentException("Unrecognized MQTT topic: " + topic);
        }
    }

    public void dispatchEvStatus(EvStatusMessage message) {
        dispatch(evStatusHandlers, h -> h.onEvStatus(message));
    }

    public void dispatchChargeRequest(ChargeRequestMessage message) {
        dispatch(chargeRequestHandlers, h -> h.onChargeRequest(message));
    }

    public void dispatchEvcsStatus(EvcsStatusMessage message) {
        dispatch(evcsStatusHandlers, h -> h.onEvcsStatus(message));
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

package com.goev.routing;

import org.springframework.stereotype.Component;

import com.goev.mqtt.ChargeRequestHandler;
import com.goev.mqtt.ChargeRequestMessage;

/**
 * Registers as a {@link ChargeRequestHandler} (so {@code MqttInboundRouter}
 * calls it directly) and delegates to whichever {@link AssignmentStrategy}
 * is active — greedy or batch, per {@code goev.routing.mode}. No routing
 * logic lives here; it's just the MQTT-facing entry point.
 */
@Component
public class RoutingCoordinator implements ChargeRequestHandler {

    private final AssignmentStrategy assignmentStrategy;

    public RoutingCoordinator(AssignmentStrategy assignmentStrategy) {
        this.assignmentStrategy = assignmentStrategy;
    }

    @Override
    public void onChargeRequest(ChargeRequestMessage request) {
        assignmentStrategy.handleChargeRequest(request);
    }
}
